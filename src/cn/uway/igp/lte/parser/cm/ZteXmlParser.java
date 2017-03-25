package cn.uway.igp.lte.parser.cm;

import java.io.IOException;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * lte中兴参数解码器(类XML)，每个xml文件对应一个数据库表，并且文件名与文件中xml节点tagName一致，
 * 每个节点的tagName在其子节点中会出现一次，解析算法通过计数器计算tagName的结束。
 * 
 * @author guom
 */
public class ZteXmlParser extends FileParser {

	/**日志记录器*/
	private static Logger LOGGER = LoggerFactory.getLogger(ZteXmlParser.class);

	private static final String myName = "中兴参数解析(类XML)";
	
	/**xml文件的文件类型标识长度，比如.xml*/
	private static final int LENGTH_XML = 4;
	
	private XMLStreamReader reader;

	/** 结果集，存储一条解析记录*/
	private Map<String, String> resultMap = null;
	
	/** 输入zip流 */
	public ZipInputStream zipstream;
	
	public ZipEntry entry = null;
	
	private String fileTag = null;
	

	/**
	 * 模板传入接口
	 * 
	 * @param tmpfilename
	 */
	public ZteXmlParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();

		zipstream = new ZipInputStream(inputStream);
		entry = zipstream.getNextEntry();
		
		if (entry == null){
			return;
		}
		/**对于厂家错误文件类型错误或者厂家文件内容生成错误不予处理，跳过*/
		if(entry.getName().endsWith(".gz"))	
		{
			return;
		}
		XMLInputFactory fac = XMLInputFactory.newInstance();
		fac.setProperty("javax.xml.stream.supportDTD", false);
		reader = fac.createXMLStreamReader(zipstream);
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		resultMap = new HashMap<String, String>();
		
		try {
			/** type记录stax解析器每次读到的对象类型，是element，还是attribute等等…… */
			int type = -1;
			
			/* 保存当前的xml标签名 */
			String tagName = null;
			/*标签对应的值*/
			String fieldValue = null;
			
			int tagCounter = 0;
			
			/**初始化解析文件对应的模板*/
			String fileName = entry.getName();
			templet = findTemplet(fileName);
			if(templet != null){
				/*根据文件名获取tagName*/
				fileTag = getTagName(fileName);
				
				/* 开始迭代读取xml文件 */
				while (reader.hasNext()) {
					try {
						type = reader.next();
					} catch (Exception e) 
					{
						continue;
					}
					if (type == XMLStreamConstants.START_ELEMENT || type == XMLStreamConstants.END_ELEMENT)
					{
						tagName = reader.getLocalName();
					}
					if (tagName == null) 
					{
						continue;
					}
					switch (type) 
					{
						case XMLStreamConstants.START_ELEMENT :
							/**对应第一次出现的根标签不予采集信息*/
							if(tagName.equals(fileTag))
							{
								tagCounter++;
							}
							if(isExistInTemplet(templet, tagName)){
								if(tagName.equals(fileTag) && tagCounter==1)
								{
									try{
										fieldValue = reader.getAttributeValue(0);
										}
									catch(Exception e)
									{
										LOGGER.warn(fileName +"文件标签解析有误，请查看厂家文件是否有误！");
									}
								}
								else
								{
									fieldValue = reader.getElementText();
								}
								resultMap.put(tagName.toUpperCase(), fieldValue);
							}
							break;
						case XMLStreamConstants.END_ELEMENT :
							/**当每条记录的根标签结束时候返回处理*/
							if(tagName.equalsIgnoreCase(fileTag))
							{
								if(tagCounter >=1)
								{
									return true;
								}
							}
							break;
						default :
							break;
					}
				}
			}
			while ((entry = zipstream.getNextEntry()) != null) {
				/**4G升级 ,测试联通北向性能时发现的问题， 如果entry.getName()为文件夹路径（不带文件名），直接跳过    ,huzq*/
				if(entry.getName().endsWith("/")){
					continue;
				}
				setCurrentDataTime(entry.getName());
				
				/**对厂家文件进行过滤，因为厂家文件可能存在生成失败的情况而导致采集停滞*/
				if(!entry.getName().endsWith(".xml"))
				{
					LOGGER.warn(entry.getName() +"厂家文件错误，跳过该文件！");
					continue;
				}
				/**对非解析模板文件不做处理*/
				if(findTemplet(entry.getName()) == null){
					LOGGER.warn(task.getId()+", fileName= "+entry.getName()+",对应的解析模板为空！厂家文件在对应的模板中没有在模板中配置");
					continue;
				}
				XMLInputFactory fac = XMLInputFactory.newInstance();
				fac.setProperty("javax.xml.stream.supportDTD", false);
				reader = fac.createXMLStreamReader(zipstream);
				return hasNextRecord();
			}
		}
		catch (Exception e) 
		{
			this.cause = "【" + myName + "】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception 
	{
		
		ParseOutRecord record = new ParseOutRecord();
		List<Field> fieldList = templet.getFieldList();
		Map<String, String> map = this.createExportPropertyMap(templet.getDataType());
		for (Field field : fieldList) {
			if (field == null) {
				continue;
			}
			String value = resultMap.get(field.getName().toUpperCase());
			
			// dataType=4035 UTRANCARRIFREQNUM字段特殊处理，xml中不存在该字段，其值为CDMACARRIFREQNUM对应值
			if("UTRANCARRIFREQNUM".equals(field.getName().toUpperCase())
					&& templet.getDataType() == 4035)
			{
				map.put(field.getIndex(), resultMap.get("CDMACARRIFREQNUM"));
				continue;
			}
			
			// 找不到，设置为空
			if (value == null) {
				map.put(field.getIndex(), "");
				continue;
			}

			// 字段值处理
			if (!fieldValHandle(field, value, map)) {
				return null;
			}

			map.put(field.getIndex(), value);
		}

		/**对于所有采集字段为空的记录，放弃入库。因为该记录入库会导致主键冲突*/
		int fieldCount = map.keySet().size();
		int nullCount = 0;
		for(String filed : map.keySet())
		{
			if(map.get(filed) == null || "".equals(map.get(filed)))
			{
				nullCount ++;
			}
		}
		if(nullCount == fieldCount)
		{
			return null;
		}
		
		
		// 公共回填字段
		map.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		handleTime(map);
		record.setType(templet.getDataType());
		record.setRecord(map);
		return record;
	}
	
	/**
	 * 功能描述：从模板中查询对应被解析文件的模板
	 * @author guom
	 * @param 解析文件名
	 * */
	private  Templet findTemplet(String fileName)
	{
		String tmpFileName = "";
		Templet templetTemp = null;
		if("".equals(fileName)|| null == fileName)
		{
			return null;
		}
		Set<String> mapSet = templetMap.keySet();
		tmpFileName = fileName.substring(fileName.indexOf("/")+1,fileName.length());
		//explain:　加入兼容新旧模板key样式
		String tmpFileKey = tmpFileName;
		int nDotPos = -1;
		if ((nDotPos = tmpFileKey.lastIndexOf('.')) > 0) {
			tmpFileKey = tmpFileKey.substring(0, nDotPos);
		}
		tmpFileKey = tmpFileKey.toUpperCase();
		
		for (String str : mapSet) 
		{
			
			if(str.equals(tmpFileName) || str.equals(tmpFileKey))
			{
				templetTemp = templetMap.get(str);
				return templetTemp;
			}
		}
		
		return null;
	}

	/**
	 * 解析文件名
	 * 
	 * @throws Exception
	 */
	public void parseFileName() 
	{
		try 
		{
			String fileName = FileUtil.getFileName(this.rawName);
			String patternTime = StringUtil.getPattern(fileName, "_\\d{8}");
			if (patternTime != null) 
			{
				patternTime=patternTime.replace("_", "");
				this.currentDataTime = TimeUtil.getyyyyMMddDate(patternTime);
			}
		} 
		catch (Exception e) 
		{
			LOGGER.debug("解析文件名异常", e);
		}
	}

	@Override
	public void close() 
	{
		/**关闭解析类文件流对象*/
		try {
			if(reader != null)
			{
				reader.close();
			}
			if(inputStream != null)
			{
				inputStream.close();
			}
			if(zipstream != null)
			{
				zipstream.close();
			}
		} catch (XMLStreamException e) 
		{
			LOGGER.warn(ZteXmlParser.class + "类关闭文件流异常！" +e.getMessage());
		}
		catch (IOException e) 
		{
			LOGGER.warn(ZteXmlParser.class + "类关闭文件流异常！" +e.getMessage());
		}
		
		// 标记解析结束时间
		this.endTime = new Date();

		LOGGER.debug("[{}]-{}，处理{}条记录", new Object[]{task.getId(), myName, readLineNum});
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		// 解析模板
		TempletParser templetParser = new TempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();

		templetMap = templetParser.getTemplets();
	}
	
	/**
	 * 查询厂家文件中的tag是否在对应的模板中存在
	 * @param templet 厂家文件对应的模板
	 * @param tagName 厂家文件中的节点
	 * */
	private boolean isExistInTemplet(Templet templet, String tagName)
	{
		
		List<Field> fieldList = templet.getFieldList();
		for (Field field : fieldList) 
		{
			if(field.getName().equalsIgnoreCase(tagName))
			{
				return true;
			}
		}
		return false;
	}
	
	/**
	 * 根据文件名获取该文件的tagName，根据分析文件名与tagName一致。
	 * @param fileName 解析文件的名称
	 * @return tagName
	 * 
	 * */
	private String getTagName(String fileName)
	{
		if("".equals(fileName)|| null == fileName)
		{
			return null;
		}
		return fileName.substring(fileName.indexOf("/")+1,fileName.length()-LENGTH_XML);
	}
	
	public static void main(String[] args) throws ParseException 
	{
		
		
	}
}