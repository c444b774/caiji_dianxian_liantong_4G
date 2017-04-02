package cn.uway.igp.lte.parser.redis;

import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.accessor.JdbcAccessOutObject;
import cn.uway.framework.cache.redis.RedisCache;
import cn.uway.framework.connection.DatabaseConnectionInfo;
import cn.uway.framework.connection.pool.database.DbPoolManager;
import cn.uway.framework.context.AppContext;
import cn.uway.framework.log.BadWriter;
import cn.uway.framework.parser.DatabaseParser;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.task.Task;
import cn.uway.framework.warehouse.destination.dao.ExportTargetDAO;
import cn.uway.framework.warehouse.exporter.template.ColumnTemplateBean;
import cn.uway.framework.warehouse.exporter.template.DatabaseExporterBean;
import cn.uway.framework.warehouse.exporter.template.ExporterBean;
import cn.uway.util.DbUtil;
import cn.uway.util.FileUtil;
import redis.clients.jedis.Jedis;


public class RedisDBParser extends DatabaseParser {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(RedisDBParser.class);
	
	/**
	 * 错误记录日志
	 */
	protected static final Logger badWriter = BadWriter.getInstance().getBadWriter();
	
	private String value = null;
	
	private RedisCache client = null;
	
	private String table;
	
	private int exportId;
	
	private int dataType;
	
	private DatabaseConnectionInfo connectionInfo = null;
	
	private String sql = null;
	
	// 在redis中要移除的members
	private String[] rems = null;
	
	private List<ColumnTemplateBean> cols = new ArrayList<ColumnTemplateBean>();
	
	public RedisDBParser() {
		
	}
	
	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		client =AppContext.getBean(AppContext.getBean("redisCahe", String.class),RedisCache.class);
		JdbcAccessOutObject outObject = (JdbcAccessOutObject)accessOutObject;
		Task task = outObject.getTask();
		
		// 解析输出模板
		parseExportTemplate("template/"+task.getExportTemplates());
		// 获取输出数据库信息
		ExportTargetDAO exportTargetDAO = AppContext.getBean("exportTargetDAO", ExportTargetDAO.class);
		Map<String, ExporterBean> exportMap = exportTargetDAO.loadDbExportTargetTemplet();
		DatabaseExporterBean dbTempletBean = (DatabaseExporterBean)exportMap.get(task.getGroupId()+"-"+dataType+"-"+exportId);
		sql = createSql();
		connectionInfo = dbTempletBean.getConnectionInfo();
		List<String> records = null;
		// == dbTempletBean.getBatchNum()
		while(true){
			try{
				records = client.srandmember(client.getDataKey(),dbTempletBean.getBatchNum());
				if(records != null && records.size() == dbTempletBean.getBatchNum()){
					exportBatch(records);
				}else{
					Thread.sleep(60000);
					records = client.srandmember(client.getDataKey(),dbTempletBean.getBatchNum());
					if(records != null && records.size() > 0){
						exportBatch(records);
					}
				}
			}
			catch(Exception e){
				LOGGER.error("将加密数据从redis存储到oracle失败：",e);
			}
		}
	}
	
	private String createSql(){
		StringBuilder insert = new StringBuilder("insert into ");
		StringBuilder value = new StringBuilder(") values(");
		insert.append(table)
			   .append("(");
		for (int i = 0; i < cols.size(); i++) {
			insert.append(cols.get(i).getColumnName());
			value.append("?");
			if(i != cols.size()-1){
				insert.append(",");
				value.append(",");
			}
		}
		value.append(")");
		return insert.append(value.toString()).toString();
	}
	
	private void exportBatch(List<String> records) throws SQLException{
		long start = System.currentTimeMillis();
		Connection con = DbPoolManager.getConnection(connectionInfo);
		PreparedStatement ps = con.prepareStatement(sql);
		con.setAutoCommit(false);
		int cdrSucc = 0;
		try {
			rems = new String[records.size()];
			for (int k=0; k < records.size();k++) {
				value = records.get(k);
				String[] params = StringUtils.split(value,":");
				if(params.length != 2){
					// 非法数据直接删除
					rems[k] = value;
					continue;
				}
				int inIndex = 0;
				for (int i = 0; i < cols.size(); i++) {
					ColumnTemplateBean column = this.cols.get(i);
					String prop = column.getPropertyName();
					if("TYPE".equals(prop)){
						ps.setString(++inIndex, params[0].substring(0, 1));
					}else if("KEY".equals(prop)){
						ps.setString(++inIndex, params[0].substring(1));
					}else if("VALUE".equals(prop)){
						ps.setString(++inIndex, params[1]);
					}
				}
				cdrSucc++;
				ps.addBatch();
				rems[k] = value;
			}
			// 批量提交一次.成功后更新断点表
			ps.executeBatch();
			con.commit();
			// 移除redis中的元素,传过去的元素不能为空，否则抛出异常
			long succ = client.srem(rems);
			// 删除redis数据失败，回滚数据库数据，避免重复入库
			LOGGER.debug("入库成功,Table={},总数={},成功条数={},非法条数={},删除redis缓存中成功的条数={},失败条数={}！"
					, new Object[]{this.table,records.size(), cdrSucc,records.size()-cdrSucc, succ,records.size()-succ});
		} catch (Exception e) {
			LOGGER.warn("Redis(t) parser exportBatch() con.commit()", e);
			if(e.getMessage().contains("ORA-00001")){
				client.srem(rems);
			}
			// 如果提交失败.则事务回滚,采用折半法查找异常的数据记录<br>
			try {
				con.rollback();
			} catch (Exception er) {
				LOGGER.warn("Redis() parser exportBatch() con.rollback(), Exception:{}", er.getMessage());
			}
			DbUtil.close(null, ps, con);
			if (records.size() > 1) {
				onException(records);
				return;
			}
			badWriter.error("入库失败,Table={},异常原因={}。记录详情:{}！", new Object[]{this.table, e.getMessage(), records.get(0)});
		} finally {
			DbUtil.close(null, ps, con);
			long end = System.currentTimeMillis();
			LOGGER.debug("本次入库消耗时间："+(end - start));
		}
	}
	
	private void parseExportTemplate(String templateFileName) throws Exception {
		if (!FileUtil.exists(templateFileName)) {
			throw new Exception("输出模板：" + templateFileName + "未找到");
		}
		FileInputStream inputStream = new FileInputStream(templateFileName);
		
		Element rootEle = new SAXReader().read(inputStream).getRootElement();

		// 加载 export 节点,构建出 ExportTemplate 对象列表
		@SuppressWarnings("unchecked")
		List<Element> exports = rootEle.elements("export");
		for (Element exportEle : exports) {
			// 输出模版ID
			if (exportEle.attributeValue("id") == null)
				throw new Exception("输出模板<export>节点无id属性，模板：" + templateFileName);
			exportId = Integer.parseInt(exportEle.attributeValue("id"));
			String data_Type = exportEle.attributeValue("dataType");
			// 数据类型 区分一个数据采集中可能存在多种数据问题
			if (data_Type != null)
				dataType = Integer.parseInt(data_Type);
			Element tableElement = exportEle.element("table");
			table = tableElement.attributeValue("value");
			Element columnss = exportEle.element("columns");
			List<?> columns = columnss.elements("column");
			if (columns == null || columns.size() == 0)
				continue;
			for (Object cObj : columns) {
				if (cObj == null || !(cObj instanceof Element))
					continue;
				Element columnEle = (Element) cObj;
				String colName = columnEle.attributeValue("name");
				String property = columnEle.attributeValue("property");
				ColumnTemplateBean column = new ColumnTemplateBean(colName,property);
				cols.add(column);
			}	
		}	
	}
	
	/**
	 * 当发生异常时执行的操作<br>
	 * 折半法进行入库，将入库失败的记录都提取出来
	 * 
	 * @param elements
	 *            异常的批次数据
	 * @throws ParseException
	 * @throws SQLException
	 */
	private void onException(List<String> records) throws SQLException {
		if (records == null || records.isEmpty())
			return;
		int size = records.size();
		if (size == 1) {
			exportBatch(records);
			return;
		}
		LOGGER.warn("发生异常，折半入库。taskId:{},recordSize:{}", task.getId(), records.size());
		// 折半处理
		exportBatch(records.subList(0, size / 2));
		exportBatch(records.subList(size / 2, size));
	}
	
	@Override
	public boolean hasNextRecord() throws Exception {
		
//		
//		if(records != null && records.size() > count){
//			return true;
//		}
//		value = client.spop(client.getDataKey(), redis);
//		if(StringUtils.isNotEmpty(value)){
//			return true;
//		}
		return false;

	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		this.totalNum++;
//		this.count++;
		Map<String, String> data = this.createExportPropertyMap(-100);
		try {
			String[] params = StringUtils.split(value,":");
			data.put("KEY", params[0]);
			data.put("VALUE", params[1]);
		} catch (ArrayIndexOutOfBoundsException e) {
			//添加驱动包里面索引越界异常捕获，将异常数据丢弃掉。
			LOGGER.warn("获取的数据非法："+e);
			return null;
		}
		ParseOutRecord outRecord = new ParseOutRecord();
		outRecord.setType(-100);
		outRecord.setRecord(data);
		return outRecord;
	}

	@Override
	public void close() {
		LOGGER.debug("[{}]-话单加密字典解析，处理{}条记录", new Object[]{task.getId(), totalNum});
	}
}
