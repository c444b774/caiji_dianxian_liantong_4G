package cn.uway.igp.lte.parser.cm;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
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
import cn.uway.igp.lte.templet.xml.LucCmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * lte 朗讯参数解码器(类XML)
 * 
 * @author yuy @ 26 May, 2014
 */
public class LucCmXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(LucCmXMLParser.class);

	public static String myName = "朗讯参数解析(类XML)";

	/** ENBEquipmentid */
	public String ENBEquipmentid;

	/** 级别（ENB/CELL） */
	public String model;

	/** 版本 */
	public String version;

	/** XML流 */
	public XMLStreamReader reader = null;

	/** 结果map */
	public Map<String, String> resultMap = null;

	/** id列表 */
	public List<String> idsList = new ArrayList<String>();

	/** <parentIds(对应parentIds属性)> */
	public Map<String, String[]> parentIdsMap;

	/** parentIdsArray */
	public String[] parentIdsArray;

	public Set<String> idNamesSet;

	/** 禁止解析标签，设立禁区 */
	public String stopParseTagName1 = "SimoResources";

	public String stopParseTagName2 = "UtraFddNeighboringFreqConf";

	/** 设立禁区标志 */
	boolean isStop = false;

	public LucCmXMLParser() {
	}

	public LucCmXMLParser(String tmpfilename) {
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
		fac.setProperty("javax.xml.stream.supportDTD", true);
		fac.setProperty("javax.xml.stream.isValidating", false);

		if (accessOutObject.getRawAccessName().endsWith(".gz")) {
			reader = fac.createXMLStreamReader(new GZIPInputStream(inputStream));
		} else {
			reader = fac.createXMLStreamReader(inputStream);
		}
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		int type = -1;
		resultMap = new HashMap<String, String>();
		boolean flag = false;
		boolean on_off = false;
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
							String val = null;
							try {
								val = StringUtil.nvl(reader.getElementText(), "");
							} catch (XMLStreamException e) {
								continue;
							}
							resultMap.put(tagName.toUpperCase(), val);
							break;
						}
						if (flag && tagName.equalsIgnoreCase("attributes")) {
							// 开关正式打开
							on_off = true;
							break;
						}
						if (findMyTemplet(tagName)) {
							// 标签SimoResources下的数据先屏蔽掉
							if (isStop)
								return hasNextRecord();
							parentIdsArray = parentIdsMap.get(tagName);
							// 开关打开
							flag = true;
							if (tagName.equalsIgnoreCase("ENBEquipment")) {
								ENBEquipmentid = StringUtil.nvl(reader.getAttributeValue(null, "id"), "");
								model = StringUtil.nvl(reader.getAttributeValue(null, "model"), "");
								version = StringUtil.nvl(reader.getAttributeValue(null, "version"), "");
								break;
							}
						}
						if (idsList == null) {
							idsList = new ArrayList<String>();
						}
						if (idNamesSet == null) {
							idNamesSet = new HashSet<String>();
						}
						String id = StringUtil.nvl(reader.getAttributeValue(null, "id"), "");
						if (!"".equals(id)) {
							idsList.add(id);
							idNamesSet.add(tagName);
						}
						if (stopParseTagName1.equalsIgnoreCase(tagName) || stopParseTagName2.equalsIgnoreCase(tagName))
							isStop = true;
						break;

					case XMLStreamConstants.END_ELEMENT :
						/** 结束 关闭禁区 */
						if (stopParseTagName1.equalsIgnoreCase(tagName) || stopParseTagName2.equalsIgnoreCase(tagName))
							isStop = false;
						if (on_off && tagName.equalsIgnoreCase("attributes")) {
							// 读取完毕，返回
							if (on_off) {
								return true;
							}
						}
						// 清除id
						if (idNamesSet.contains(tagName)) {
							// 遇到结束就清除
							if (idsList.size() > 1) {
								idsList.remove(idsList.size() - 1);
								idNamesSet.remove(tagName);
								break;
							}
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
		if (templet == null)
			return null;
		ParseOutRecord record = new ParseOutRecord();
		List<Field> fieldList = templet.getFieldList();
		// Map<String, String> map = new HashMap<String, String>();
		Map<String, String> map = this.createExportPropertyMap(templet.getDataType());
		for (Field field : fieldList) {
			if (field == null) {
				continue;
			}
			String value = resultMap.get(field.getName().trim().toUpperCase());
			// 找不到，设置为空
			if (value == null) {
				map.put(field.getIndex(), "");
				continue;
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
		resultMap.clear();
		resultMap = null;
		return record;
	}

	/**
	 * 相关网元信息
	 * 
	 * @param map
	 */
	public void putOtherInfo(Map<String, String> map) {
		map.put("ENBEQUIPMENTID", ENBEquipmentid);
		map.put("MODEL", model);
		map.put("VERSION", version);

		if (parentIdsArray == null)
			return;
		// 根元素字段值入库
		int j = 0;
		for (int n = idsList.size() - parentIdsArray.length; n < idsList.size(); n++) {
			if (n < 0)
				continue;
			map.put(parentIdsArray[j].toUpperCase(), idsList.get(n));
			j++;
		}
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("[{}]-{}，处理{}条记录", new Object[]{task.getId(), myName, readLineNum});
	}

	/**
	 * 找到当前对应的Templet
	 */
	public boolean findMyTemplet(String tagName) {
		templet = templetMap.get(tagName);// 这里的key全部转为大写字母
		if (templet == null) {
			// LOGGER.debug("没有找到对应的模板，跳过，vsDataType:{}", vsDataType);
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
			String patternTime = StringUtil.getPattern(fileName, "\\d{4}[-]\\d{2}[-]\\d{2}");
			patternTime = patternTime.replace("-", "");
			if (patternTime != null) {
				this.currentDataTime = TimeUtil.getyyyyMMddDate(patternTime);
			}
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
		LucCmXmlTempletParser templetParser = new LucCmXmlTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();

		templetMap = templetParser.getTemplets();
		parentIdsMap = templetParser.parentIdsMap;
	}
}
