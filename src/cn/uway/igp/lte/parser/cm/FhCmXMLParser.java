package cn.uway.igp.lte.parser.cm;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * 烽火LTE参数XML格式解析器
 * 
 * @author Niow
 * 
 */
public class FhCmXMLParser extends FileParser{

	private static Logger LOGGER = LoggerFactory.getLogger(FhCmXMLParser.class);

	private Map<String,String> commonFields = new HashMap<String,String>();

	private List<String> fieldNameList = new ArrayList<String>();

	private Map<String,String> fieldMap;

	private String myName = "烽火参数XML解析";

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
			if(entry == null)
				return;

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

		readHeaderToComm(commonFields);
		readTypeToComm(commonFields);
		String type = commonFields.get("OBJECTTYPE");
		templet = templetMap.get(type);
		if(templet == null){
			LOGGER.error("没有找到相关模板，跳过[" + type + "]");
		}
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
		if(templet == null){
			return false;
		}
		fieldMap = readField();
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
	 * 读取文件头中的内容存储到公共字段
	 * 
	 * @param commonFields
	 * @throws IOException
	 */
	private void readHeaderToComm(Map<String,String> commonFields) throws Exception{
		Map<String,String> map = readTag("FileHeader");
		commonFields.putAll(map);
	}

	/**
	 * 读取文件类型和字段信息到公共字段
	 * 
	 * @param commonFields
	 * @throws IOException
	 */
	private void readTypeToComm(Map<String,String> commonFields) throws Exception{
		Map<String,String> typeMap = readTag("ObjectType");
		commonFields.putAll(typeMap);

		Map<String,String> fieldNameMap = readTag("FieldName");
		for(String value : fieldNameMap.values()){
			fieldNameList.add(value.toUpperCase());
		}

	}

	/**
	 * 读取字段信息
	 * 
	 * @return
	 * @throws IOException
	 */
	private Map<String,String> readField() throws Exception{
		Map<String,String> fields = readTag("Cm");
		for(int i = 0; fields != null && i < fieldNameList.size(); i++){
			String name = fieldNameList.get(i);
			String value = fields.get(String.valueOf(i + 1));
			fields.put(name, value);
		}
		return fields;
	}

	private Map<String,String> readTag(String targetTag) throws Exception{
		int type = -1;
		String tagName = "";
		boolean begin = false;
		Map<String,String> map = new LinkedHashMap<String,String>();
		try{
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
					readAttributes(map);
					reader.next();
					if(reader.isCharacters()){
						String name = tagName.toUpperCase();
						String value = reader.getText();
						if(StringUtil.isNotEmpty(value)){
							map.put(name, value);
						}
					}else if(reader.isStartElement()){
						readStartElement(map);
					}
					begin = true;
				}else if(type == XMLStreamConstants.END_ELEMENT && tagName.equalsIgnoreCase(targetTag)){
					return map;
				}else if(begin && type == XMLStreamConstants.START_ELEMENT){
					readStartElement(map);
				}
			}
		}catch(XMLStreamException e){
			this.cause = "【" + myName + "】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
		return null;
	}

	private void readStartElement(Map<String,String> map) throws Exception{
		String name = reader.getLocalName();
		if(name.equals("N") || name.equals("V")){
			name = reader.getAttributeValue(0);
		}
		String value = reader.getElementText();
		map.put(name.toUpperCase(), value);
	}

	/**
	 * 读取节点的属性
	 * 
	 * @param map
	 */
	private void readAttributes(Map<String,String> map){
		int count = reader.getAttributeCount();
		for(int i = 0; i < count; i++){
			String attributeLocalName = reader.getAttributeLocalName(i);
			String value = reader.getAttributeValue(i);
			map.put(attributeLocalName.toUpperCase(), value);
		}
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
