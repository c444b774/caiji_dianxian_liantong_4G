package cn.uway.igp.lte.parser.M2000.hw;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.CSVCfcTempletParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * @author yuy 2014.3.6 lte 华为M2000北向存量(stock)文件解码器(xml格式)
 */
public class HwStockXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(HwStockXMLParser.class);

	/**
	 * 网元级别
	 */
	public String neType;

	/**
	 * 网元名称
	 */
	public String neName;

	/**
	 * 存量类型
	 */
	public String tableName;

	/**
	 * 名称
	 */
	public String measObjLdn;

	/**
	 * 性能指标结果集
	 */
	public String measResults;

	/**
	 * xml流
	 */
	public XMLStreamReader reader = null;

	/**
	 * 存放一条数据
	 */
	public Map<String, String> resultMap = null;

	/**
	 * 模板传入接口
	 * 
	 * @param tmpfilename
	 */
	public HwStockXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();

		reader = XMLInputFactory.newInstance().createXMLStreamReader(inputStream);
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		int type = -1;
		resultMap = new HashMap<String, String>();
		try {
			while (reader.hasNext()) {
				type = reader.next();
				String tagName = null;

				// 只取开始和结束标签
				if (type == XMLStreamConstants.START_ELEMENT || type == XMLStreamConstants.END_ELEMENT)
					tagName = reader.getLocalName();

				if (tagName == null) {
					continue;
				}
				switch (type) {
					case XMLStreamConstants.START_ELEMENT :
						if (tagName.equalsIgnoreCase("NE")) {
							// 获取网元名称
							neName = StringUtil.nvl(reader.getAttributeValue(null, "NEName"), "");
							// 获取网元级别
							neType = StringUtil.nvl(reader.getAttributeValue(null, "NEType"), "");
							break;
						}
						if (tagName.equalsIgnoreCase("TABLE")) {
							// 获取数据类别(表名)
							tableName = StringUtil.nvl(reader.getAttributeValue(null, "attrname"), "").toUpperCase();
							if (!findMyTemplet(tableName)) {
								// 找不到对应的模板，丢弃
								tableName = null;
								continue;
							}
							break;
						}
						if (tagName.equalsIgnoreCase("ROW")) {
							// 获取性能各指标名称和值
							for (int n = 0; n < reader.getAttributeCount(); n++) {
								resultMap.put(reader.getAttributeLocalName(n).toUpperCase(), reader.getAttributeValue(n));
							}
							return true;
						}
						break;

					default :
						break;
				}
			}
		} catch (XMLStreamException e) {
			this.cause = "【华为存量XML解码】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		readLineNum++;
		ParseOutRecord record = new ParseOutRecord();
		List<Field> fieldList = templet.getFieldList();
		// Map<String, String> map = new HashMap<String, String>();
		Map<String, String> map = this.createExportPropertyMap(templet.getDataType());
		for (Field field : fieldList) {
			if (field == null) {
				continue;
			}
			String value = resultMap.get(field.getName().toUpperCase());
			// 找不到，设置为空
			if (value == null) {
				map.put(field.getIndex(), "");
				continue;
			}
			map.put(field.getIndex(), value);
		}

		// 公共回填字段
		map.put("MME_ID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		handleTime(map);
		record.setType(templet.getDataType());
		record.setRecord(map);
		return record;
	}

	/**
	 * 找到当前对应的Templet
	 */
	public boolean findMyTemplet(String measInfoId) {
		templet = templetMap.get(measInfoId);// 这里的key全部转为大写字母
		if (templet == null) {
			// throw new NullPointerException("没有找到对应的模板，解码退出");
			LOGGER.debug("没有找到对应的模板，跳过，measInfoId:{}", measInfoId);
			return false;
		}
		return true;
	}

	/**
	 * 解析文件名
	 * 
	 * @throws Exception
	 */
	public void parseFileName() {
		try {
			String fileName = FileUtil.getFileName(this.rawName);
			String[] str = StringUtil.split(fileName, "_");
			this.currentDataTime = TimeUtil.getyyyyMMddHHDate(str[str.length - 1]);
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		// 解析模板
		TempletParser csvTempletParser = new CSVCfcTempletParser();
		csvTempletParser.tempfilepath = templates;
		csvTempletParser.parseTemp();

		templetMap = csvTempletParser.getTemplets();
	}
}
