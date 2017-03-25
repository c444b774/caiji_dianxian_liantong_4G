package cn.uway.igp.lte.parser.cm;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
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
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.igp.lte.templet.CommonTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * 烽火LTE参数NS标签格式的XML格式解析器
 * 
 * @author Niow
 * 
 */
public class FhCmNSXMLParser extends FileParser{

	private static Logger LOGGER = LoggerFactory.getLogger(FhCmNSXMLParser.class);

	private Map<String,String> fieldMap;

	/**
	 * 存放模板字段map
	 */
	private Map<String,HashMap<String,Field>> templetFieldMaps = new HashMap<String,HashMap<String,Field>>();
	
	/**
	 * 存放单个模板的字段
	 */
	private HashMap<String,Field>templetFieldMap;

	private String myName = "烽火参数NS标签XML解析";

	/** 输入流(ZIP), 当输入流是ZIP文件格式时，该流有效 */
	public ZipInputStream zipstream = null;

	/** ZIP包中的子文件，和ZIP流同时存在 */
	public ZipEntry entry = null;

	/** 当前解码的文件名 */
	public String entryFileName = null;

	/** 输入流 */
	public InputStream rawFileStream;

	/** XML流 */
	public XMLStreamReader reader = null;

	/**
	 * 总共输出数据条数
	 */
	private int count;

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * cn.uway.framework.parser.file.FileParser#parse(cn.uway.framework.accessor
	 * .AccessOutObject)
	 */
	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception{
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// currentDataTime 用于生成summary文件进行日期匹配
		this.currentDataTime = this.getCurrentDataTime();

		// 解析模板 获取当前文件对应的Templet
		parseTemplet();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());
		// 如果是ZIP压缩包，则可能是解多个文件，所以要用ZipInputStream;
		if(accessOutObject.getRawAccessName().toLowerCase().endsWith(".zip")){
			this.zipstream = new ZipInputStream(inputStream);
			entry = zipstream.getNextEntry();
			if(entry == null){
				return;
			}
			this.rawFileStream = zipstream;
			LOGGER.debug("开始解析子文件:{}, ZIP文件：{}", entry.getName(), this.rawName);
			extractCommFieldByFileName(entry.getName());
		}else if(accessOutObject.getRawAccessName().toLowerCase().endsWith(".gz")){
			this.rawFileStream = new GZIPInputStream(inputStream);
			extractCommFieldByFileName(accessOutObject.getRawAccessName());
		}else{
			this.rawFileStream = inputStream;
			extractCommFieldByFileName(accessOutObject.getRawAccessName());
		}

		XMLInputFactory fac = XMLInputFactory.newInstance();
		fac.setProperty("javax.xml.stream.supportDTD", false);
		this.reader = fac.createXMLStreamReader(this.rawFileStream);

	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception{
		// 解析模板
		TempletParser templetParser = new CommonTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();
		templetMap = templetParser.getTemplets();
		
	}

	@Override
	public boolean hasNextRecord() throws Exception{
		fieldMap = readHead();
		if(fieldMap == null){
			return false;
		}
		String clazz = fieldMap.get("class");
		templet = templetMap.get(clazz);
		while(templet == null)
		{
			LOGGER.info("没有找到相关模板，跳过[" + clazz + "]");
			fieldMap = readHead();
			if(fieldMap == null){
				return false;
			}
			clazz = fieldMap.get("class");
			templet = templetMap.get(clazz);	
		}
		templetFieldMap = templetFieldMaps.get(clazz);
		if(templetFieldMap == null){
			templetFieldMap = new HashMap<String,Field>();
			for(int i = 0;templet.getFieldList() != null && i < templet.getFieldList().size(); i++){
				Field field = templet.getFieldList().get(i);
				templetFieldMap.put(field.getName(), field);
			}
			templetFieldMaps.put(clazz, templetFieldMap);
		}
		readField(fieldMap);
		if(fieldMap == null){
			return false;
		}
		return true;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception{
		ParseOutRecord record = new ParseOutRecord();
		record.setType(templet.getDataType());
		List<Field> fieldList = templet.getFieldList();
		Map<String,String> map = this.createExportPropertyMap(templet.getDataType());
		for(Field field : fieldList){
			if(field == null){
				continue;
			}
			String value = fieldMap.get(field.getName().trim().toUpperCase());
			// 找不到，设置为空
			if(value == null){
				map.put(field.getName(), "");
				continue;
			}
			// 字段值处理
			if(!fieldValHandle(field, value, map)){
				invalideNum++;
				return null;
			}
			
			// 16进制转换成10进制
			if("OBJECTINSTANCE".equals(field.getName().trim().toUpperCase()))
			{
				if(map.get("MANAGEDELEMENT") != null)
				{
					map.put("MANAGEDELEMENT",String.valueOf(Long.parseLong(map.get("MANAGEDELEMENT"), 16)));
				}
				if(map.get("ENBFUNCTION") != null)
				{
					map.put("ENBFUNCTION",String.valueOf(Long.parseLong(map.get("ENBFUNCTION"), 16)));
				}
				if(map.get("EUTRANCELLTDD") != null)
				{
					map.put("EUTRANCELLTDD",String.valueOf(Long.parseLong(map.get("EUTRANCELLTDD"), 16)));
				}
			}
			
			// SubNetwork InventoryUnitRru ManagementNode以外ID由16进制转换为10进制
			if("ID".equals(field.getName().trim().toUpperCase())&&value != null)
			{
				if(!("SubNetwork".equals(fieldMap.get("class")) 
						|| "ManagementNode".equals(fieldMap.get("class")) 
						|| "InventoryUnitRru".equals(fieldMap.get("class"))))
				{
					value = String.valueOf(Long.parseLong(value,16));
				}
			}
			map.put(field.getName(), value);
		}
		// 公共回填字段
		map.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		map.put("STAMPTIME", TimeUtil.getyyyyMMddDateString(this.currentDataTime));
		record.setRecord(map);
		count++;
		return record;
	}

	/**
	 * 读取字段信息
	 * 
	 * @return
	 * @throws IOException
	 */
	private void readField(Map<String,String> fieldMap) throws Exception{
		Map<String,String> fields = readTag("attributes");
		if(fields == null || fields.isEmpty()){
			fieldMap = null;
		}else{
			fieldMap.putAll(fields);
		}
	}

	private Map<String,String> readHead() throws Exception{
		int type = -1;
		String tagName = "";
		Map<String,String> map = new LinkedHashMap<String,String>();
		try{
			while(reader.hasNext()){
				type = reader.next();
				// 只取开始和结束标签
				if(type == XMLStreamConstants.START_ELEMENT){
					int count = reader.getAttributeCount();
					if(count == 0){
						continue;
					}
					String attributeLocalName = reader.getAttributeLocalName(0);
					if(!"id".equalsIgnoreCase(attributeLocalName)){
						continue;
					}
					tagName = reader.getLocalName();
					map.put("ID", reader.getAttributeValue(0));
					map.put("class", tagName);
					return map;
				}
			}
		}catch(XMLStreamException e){
			this.cause = "【" + myName + "】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
		return null;
	}

	private Map<String,String> readTag(String targetTag) throws Exception{
		int type = -1;
		boolean begin = false;
		String tagName = "";
		Map<String,String> map = new LinkedHashMap<String,String>();
		try{
			LinkedList<String> fieldName = new LinkedList<String>();
			while(reader.hasNext()){
				type = reader.next();
				// 只取开始和结束标签
				if(type == XMLStreamConstants.START_ELEMENT || type == XMLStreamConstants.END_ELEMENT){
					tagName = reader.getLocalName();
				}
				if(tagName == null){
					continue;
				}
				if(type == XMLStreamConstants.START_ELEMENT && tagName.equalsIgnoreCase(targetTag)){
					begin = true;
				}else if(type == XMLStreamConstants.END_ELEMENT && tagName.equalsIgnoreCase(targetTag)){
					if(templet.getDataName().equalsIgnoreCase("SCTPASSOC")){
						System.out.println(map.toString());
					}
					return map;
				}else if(begin){
					if(type == XMLStreamConstants.START_ELEMENT){
						fieldName.add(tagName.toUpperCase());
						int next = reader.next();
						if(reader.isCharacters()){
							String name = getFieldName(fieldName);
							Field field = templetFieldMap.get(name);
							if(field == null ){
								continue;
							}
							String index = field.getIndex();
							String value = reader.getText();
							
							if(StringUtil.isNotEmpty(value)){
								String existValue = map.get(index);
								templetFieldMaps.get(index);
								if(StringUtil.isEmpty(existValue)){
									map.put(index, value);
								}else{
									value = existValue + "|" + value;
									map.put(index, value);
								}
							}
						}else if(reader.isStartElement()){
							String name = reader.getLocalName();
							fieldName.add(name);
						}else if(next == XMLStreamConstants.END_ELEMENT){
							fieldName.removeLast();
						}
					}else if(type == XMLStreamConstants.END_ELEMENT){
						fieldName.removeLast();
					}
				}
			}
		}catch(XMLStreamException e){
			this.cause = "【" + myName + "】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
		return null;
	}

	private String getFieldName(List<String> fieldName){
		StringBuilder name = new StringBuilder();
		for(int i = 0; i < fieldName.size(); i++){
			name.append(fieldName.get(i));
			if(i < fieldName.size() - 1){
				name.append(".");
			}
		}
		return name.toString();
	}

	/**
	 * 解析文件名时间
	 * 
	 * @throws Exception
	 */
	public void extractCommFieldByFileName(String fileEntryName){
		try{
			String fileName = FileUtil.getFileName(fileEntryName);
			this.entryFileName = fileName;
			String patternTime = StringUtil.getPattern(fileName, "[-]\\d{8}[-]\\d{4}");
			if(patternTime != null){
				// 先从文件名是截取一个默认的时间, 真正最后入库的还是从原始文件解析出来的时间.
				this.currentDataTime = TimeUtil.getyyyyMMddHorizontalLineHHmmDate(patternTime.substring(1));
			}
		}catch(Exception e){
			LOGGER.debug("解析文件名异常", e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see cn.uway.framework.parser.file.FileParser#close()
	 */
	@Override
	public void close(){
		// TODO Auto-generated method stub
		super.close();
		LOGGER.debug(this.entryFileName + "解析完毕，总共解析条数:" + count);
	}
	
}
