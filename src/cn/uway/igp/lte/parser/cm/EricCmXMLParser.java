package cn.uway.igp.lte.parser.cm;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

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
import cn.uway.igp.lte.templet.xml.EricCmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * 爱立信参数XML格式解码器
 * 
 * @author yuy @ 23 May, 2014
 */
public class EricCmXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(EricCmXMLParser.class);

	public static String myName = "爱立信参数XML解析";

	/** enodB名称 */
	public String ENBNAME;

	/** 小区名称 */
	public String CELLNAME;

	/** 设备id */
	public String EquipmentID;

	/** ManagedElementID */
	public String ManagedElementID;

	/** parentId */
	public String parentId;

	/** 父标签名 */
	public String parentTagName;

	/** 数据类型 */
	public String vsDataType;

	/** 读取开关 */
	public boolean on_off = false;

	/** XML流 */
	public XMLStreamReader reader = null;

	/** 结果map */
	public Map<String, String> resultMap = null;

	/** 存储XML层级结构 */
	public List<String> xmlDataStruList;

	/** Map<vsDataType,indexsArray> */
	public Map<String, String[]> indexsMap;

	/** indexsArray */
	public String[] indexsArray;

	public static Map<String, Set<String>> dataStructMap;

	/** 个性化标签1 */
	public static String personlyFromTagName1 = "ueMeasIntraFreq1";

	/** 个性化标签2 */
	public static String personlyFromTagName2 = "ueMeasIntraFreq2";
	
	// 用于临时存放ueMeasIntraFreq1的值，后续用于判断ueMeasIntraFreq2的值是否与ueMeasIntraFreq1重复
	public Map<String,String> tmpMap = null;
	
	public static final String vsDataPmUeMeasControl = "VSDATAPMUEMEASCONTROL";
	
	public boolean isFreq2 = false;
	
	/**
	 * 特殊处理标签
	 */
	public static String special_dataType1 = "vsDataSectorEquipmentFunction";
	public static String special_dataType1_filedName = "rfBranchRef";
	public List<String> nextRecordValuesList = new LinkedList<String>();

	/** 个性化标志，开关，默认关闭 */
	public boolean personlyFlag = false;

	public EricCmXMLParser() {
	}

	public EricCmXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();

		/* 创建STAX解析器 */
		XMLInputFactory fac = XMLInputFactory.newInstance();
		if (accessOutObject.getRawAccessName().endsWith(".gz")) {
			reader = fac.createXMLStreamReader(new GZIPInputStream(inputStream));
		} else {
			reader = fac.createXMLStreamReader(inputStream);
		}
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		if (nextRecordValuesList.size()>0) { 
			if (resultMap != null && resultMap.size()>0) {
				String value = nextRecordValuesList.remove(0);
				resultMap.put(special_dataType1_filedName.toUpperCase(), value);
				
				return true;
			} else {
				nextRecordValuesList.clear();
			}
		}
		
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
						/** 开始读入内容 */
						if (on_off) {
							// 如果不是解析模板中包含的字段，跳过
							if (!dataStructMap.get(vsDataType).contains(tagName.toUpperCase())) {
								if (tagName.equals(personlyFromTagName1))
									personlyFlag = true;
								else if (tagName.equals(personlyFromTagName2))
									personlyFlag = false;
								continue;
							}
							String str = null;
							try {
								str = reader.getElementText();
							} catch (Exception e) {
								// 读不到内容，表示还有子标签，跳出，继续遍历
								break;
							}
							String val = StringUtil.nvl(str, "");
							// 同一个元素下同一字段存在多行,数据做合并处理
							String v = resultMap.get(tagName.toUpperCase());
							if (v == null || v.length() < 1) {
								resultMap.put(tagName.toUpperCase(), val);
							} else if (tagName.equalsIgnoreCase(parentTagName)) {
								if (vsDataType.equalsIgnoreCase(special_dataType1) && tagName.equalsIgnoreCase(special_dataType1_filedName)) {
									nextRecordValuesList.add(val);
								} else {
									//TODO:由于合并后，很多字段的类型为NUMBER,导致不能入库，所以暂时先不合并
									
									//if (tagName.equalsIgnoreCase("a5Threshold1Rsrp") || tagName.equalsIgnoreCase("a5Threshold2Rsrp")) {
									//	resultMap.put(tagName.toUpperCase(), v + ";" + val);
									//}
								}
							}
							parentTagName = tagName;
							break;
						}
						// 数据容器
						if (tagName.equalsIgnoreCase("VsDataContainer")) {
							// 获取parentId
							parentId = StringUtil.nvl(reader.getAttributeValue(null, "id"), "");
							if (xmlDataStruList != null)
								xmlDataStruList.add(parentId);
							break;
						}
						// 数据类型
						if (tagName.equalsIgnoreCase("vsDataType")) {
							vsDataType = StringUtil.nvl(reader.getElementText(), "").toUpperCase();
							if (vsDataType.equalsIgnoreCase("vsDataEUtranCellFDD") || vsDataType.equalsIgnoreCase("vsDataEUtranCellTDD")) {
								CELLNAME = parentId;
							} else if (vsDataType.equalsIgnoreCase("vsDataEquipment"))
								EquipmentID = parentId;
							break;
						}
						// 找到数据开始标签
						if (tagName.equalsIgnoreCase(vsDataType)) {
							// 寻找对应的模板
							if (findMyTemplet(vsDataType)) {
								indexsArray = indexsMap.get(vsDataType);
								/** 读取开关开启 */
								on_off = true;
							}
							break;
						}
						// enodB级别数据
						if (tagName.equalsIgnoreCase("MeContext")) {
							// 获取enodB名称
							ENBNAME = StringUtil.nvl(reader.getAttributeValue(null, "id"), "");
							// 开始计入链表
							xmlDataStruList = new ArrayList<String>();
							xmlDataStruList.add(ENBNAME);
							break;
						}
						// 设备数据
						if (tagName.equalsIgnoreCase("ManagedElement")) {
							// 获取parentId
							parentId = StringUtil.nvl(reader.getAttributeValue(null, "id"), "");
							if (xmlDataStruList != null)
								xmlDataStruList.add(parentId);
							parentTagName = tagName;
							ManagedElementID = parentId;
							break;
						}
						// 额外读取设备配置数据
						if ("ManagedElement".equalsIgnoreCase(parentTagName)) {
							if (tagName.equalsIgnoreCase("attributes")) {
								break;
							}
							String val = StringUtil.nvl(reader.getElementText(), "");
							resultMap.put(tagName.toUpperCase(), val);
							break;
						}
						break;

					case XMLStreamConstants.END_ELEMENT :
						// vsDataPmUeMeasControl下有两条数据，特殊处理
						if (personlyFlag && tagName.equals(personlyFromTagName1)) {
							/** 读完开关关闭 */
							if (on_off) {
								return true;
							}
							break;
						}

						// 数据读取完毕
						if (tagName.equalsIgnoreCase(vsDataType)) {
							// 第二个ueMeasIntraFreq2为一条合法记录
							if(vsDataPmUeMeasControl.equals(vsDataType) 
									&& !personlyFlag 
									&& tmpMap != null 
									&& on_off 
									&& ENBNAME != null){
									isFreq2 = true;
							}
							vsDataType = null;
							/** 读完开关关闭 */
							if (on_off) {
								on_off = false;
								// ENBNAME为空，丢掉
								if (ENBNAME == null) {
									invalideNum++;
									return hasNextRecord();
								}
								return true;
							}
							break;
						}
						if (tagName.equalsIgnoreCase("VsDataContainer")) {
							if (xmlDataStruList != null)
								xmlDataStruList.remove(xmlDataStruList.size() - 1);
							break;
						}
						if (tagName.equalsIgnoreCase("ManagedElement")) {
							if (xmlDataStruList != null)
								xmlDataStruList.remove(xmlDataStruList.size() - 1);
							break;
						}
						if (tagName.equalsIgnoreCase("MeContext")) {
							if (xmlDataStruList != null)
								xmlDataStruList.remove(xmlDataStruList.size() - 1);
							break;
						}
						if (tagName.equalsIgnoreCase("attributes") && "ManagedElement".equalsIgnoreCase(parentTagName)) {
							parentTagName = null;
							break;
						}
						break;

					default :
						break;
				}
			}
		} catch (XMLStreamException e) {
			this.cause = "【" + myName + "】IO读文件发生异常：" + e.getMessage();
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
			//String value = resultMap.get(field.getName().trim().toUpperCase());
			String value = resultMap.get(field.getIndex());
			// 找不到，设置为空
			if (value == null) {
				map.put(field.getIndex(), "");
				continue;
			}
			// 字段值处理
			if (!fieldValHandle(field, value, map)) {
				invalideNum++;
				return null;
			}
			map.put(field.getIndex(), value);
		}

		// 公共回填字段
		map.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		handleTime(map);

		// 网元信息
		putOtherInfo(map);

		record.setType(templet.getDataType());
		record.setRecord(map);
//		此处注释掉，还有些记录些要复用这个resultMap;
//		if (!personlyFlag) {
//			resultMap.clear();
//			resultMap = null;
//		}
		if(personlyFlag){
			tmpMap = map;
		}
		// 第一个ueMeasIntraFreq1与第二个ueMeasIntraFreq2相同时只入库一条记录，否则出现唯一性约束
		if(isFreq2){
			if((tmpMap.get("REPORTCONFIGEUTRAINTRAFREQPMREF") == null && map.get("REPORTCONFIGEUTRAINTRAFREQPMREF") == null)||(tmpMap.get("REPORTCONFIGEUTRAINTRAFREQPMREF") != null 
					&& tmpMap.get("REPORTCONFIGEUTRAINTRAFREQPMREF").equals(map.get("REPORTCONFIGEUTRAINTRAFREQPMREF")))){
				
				if((tmpMap.get("EUTRANFREQUENCY") == null && map.get("EUTRANFREQUENCY") == null)
						|| (tmpMap.get("EUTRANFREQUENCY") != null 
						&&  tmpMap.get("EUTRANFREQUENCY").equals(map.get("EUTRANFREQUENCY")))){
					isFreq2 = false;
					tmpMap = null;
					return null;
				}else{
					isFreq2 = false;
					tmpMap = null;
				}
			
			}else{
				isFreq2 = false;
				tmpMap = null;
			}
		}		 		 
		
		return record;
	}

	/**
	 * 相关网元信息
	 * 
	 * @param map
	 */
	public void putOtherInfo(Map<String, String> map) {
		// endoB名称
		map.put("ENBNAME", ENBNAME);
		// 小区名称
		map.put("CELLNAME", CELLNAME);

		map.put("EQUIPMENTID", EquipmentID);
		map.put("MANAGEDELEMENTID", ManagedElementID);

		// 根元素字段值入库
		int j = 0;
		if (xmlDataStruList != null) {
			for (int n = xmlDataStruList.size() - indexsArray.length; n < xmlDataStruList.size(); n++) {
				if (n < 0)
					continue;
				map.put(indexsArray[j].toUpperCase(), xmlDataStruList.get(n));
				j++;
			}
			// 处理vsDataType="vsDataEUtranFreqRelation"的情况，FREQUENCYID与ID同值
			if ("".equals(map.get("ID")) && Arrays.asList(indexsArray).indexOf("ID") == -1)
				map.put("ID", xmlDataStruList.get(xmlDataStruList.size() - 1));
		}
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("[{}]-{}，处理{}条记录，无效记录{}条", new Object[]{task.getId(), myName, readLineNum, invalideNum});
	}

	/**
	 * 找到当前对应的Templet
	 */
	public boolean findMyTemplet(String vsDataType) {
		templet = templetMap.get(vsDataType);// 这里的key全部转为大写字母
		if (templet == null) {
			// LOGGER.debug("没有找到对应的模板，跳过，vsDataType:{}", vsDataType);
			return false;
		}

		// 获取解析的字段
		if (dataStructMap == null)
			dataStructMap = new HashMap<String, Set<String>>();
		Set<String> set = dataStructMap.get(vsDataType);
		if (set == null) {
			set = new HashSet<String>();
			dataStructMap.put(vsDataType, set);
		} else {
			return true;
		}
		for (Field field : templet.getFieldList()) {
			if (field == null) {
				continue;
			}
			if (field.getName() != null)
				set.add(field.getName());
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
			String patternTime = StringUtil.getPattern(fileName, "\\d{8}");
			if (patternTime != null) {
				this.currentDataTime = TimeUtil.getyyyyMMddDate(patternTime);
			} else {
				patternTime = StringUtil.getPattern(this.rawName, "\\d{8}");
				this.currentDataTime = TimeUtil.getyyyyMMddDate(patternTime);
			}
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	// 将时间转换成format格式的Date
	public final Date getDateTime(String date, String format) {
		if (date == null) {
			return null;
		}
		if (format == null) {
			format = "yyyy-MM-dd HH:mm:ss";
		}
		try {
			DateFormat df = new SimpleDateFormat(format);
			return df.parse(date);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		// 解析模板
		EricCmXmlTempletParser templetParser = new EricCmXmlTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();

		templetMap = templetParser.getTemplets();
		indexsMap = templetParser.indexsMap;
	}
}