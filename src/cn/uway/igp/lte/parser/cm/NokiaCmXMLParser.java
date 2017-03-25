package cn.uway.igp.lte.parser.cm;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
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
import cn.uway.igp.lte.templet.xml.NokiaCmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class NokiaCmXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(LucCmXMLParser.class);

	public static String myName = "诺西参数XML解析";

	/** XML流 */
	public XMLStreamReader reader = null;

	/** 数据记录map */
	public Map<String, String> dataRecordMap = null;

	/** 标签p的name相同，而list的name不同（做下区分，p的name前面加listName + "_"） */
	public static Set<String> MultiListNames = new HashSet<String>();
	static {
		MultiListNames.add("sibSchedulingList");
		MultiListNames.add("sib2Scheduling");
		MultiListNames.add("sib3Scheduling");
		MultiListNames.add("bcPlmnIdList");
		MultiListNames.add("ecgiPlmnId");
		MultiListNames.add("guGroupIdList");
		MultiListNames.add("plmnId");

		MultiListNames.add("amRlcPBTab1");
		MultiListNames.add("amRlcPBTab2");
		MultiListNames.add("amRlcPBTab3");
		MultiListNames.add("amRlcPBTab4");
		MultiListNames.add("amRlcPBTab5");
		MultiListNames.add("cipherPrefL");
		MultiListNames.add("integrityPrefL");
		MultiListNames.add("pdcpProf1");
		MultiListNames.add("rlcProf1");
		MultiListNames.add("pdcpProf101");
		MultiListNames.add("rlcProf101");
		MultiListNames.add("pdcpProf102");
		MultiListNames.add("rlcProf102");
		MultiListNames.add("pdcpProf103");
		MultiListNames.add("rlcProf103");
		MultiListNames.add("pdcpProf104");
		MultiListNames.add("rlcProf104");
		MultiListNames.add("pdcpProf2");
		MultiListNames.add("rlcProf2");
		MultiListNames.add("pdcpProf3");
		MultiListNames.add("rlcProf3");
		MultiListNames.add("pdcpProf4");
		MultiListNames.add("rlcProf4");
		MultiListNames.add("pdcpProf5");
		MultiListNames.add("rlcProf5");
		MultiListNames.add("qciTab1");
		MultiListNames.add("qciTab2");
		MultiListNames.add("qciTab3");
		MultiListNames.add("qciTab4");
		MultiListNames.add("qciTab5");
		MultiListNames.add("qciTab6");
		MultiListNames.add("qciTab7");
		MultiListNames.add("qciTab8");
		MultiListNames.add("qciTab9");

		MultiListNames.add("drxProfile1");
		MultiListNames.add("drxProfile101");
		MultiListNames.add("drxProfile102");
		MultiListNames.add("drxProfile2");
		MultiListNames.add("drxProfile3");
		MultiListNames.add("drxProfile4");
		MultiListNames.add("drxProfile5");
		MultiListNames.add("drxSmartProfile2");
		MultiListNames.add("drxSmartProfile3");
		MultiListNames.add("drxSmartProfile4");
		MultiListNames.add("drxSmartProfile5");

		MultiListNames.add("trafficTypesMap");
		MultiListNames.add("dscpMap");
	}

	public NokiaCmXMLParser() {
	}

	public NokiaCmXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.before();
		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());
		// 解析模板 获取当前文件对应的templet
		parseTemplet();
		XMLInputFactory fac = XMLInputFactory.newInstance();
		fac.setProperty("javax.xml.stream.supportDTD", false);
		if (accessOutObject.getRawAccessName().endsWith(".gz")) {
			reader = fac.createXMLStreamReader(new GZIPInputStream(inputStream));
		} else if (accessOutObject.getRawAccessName().endsWith(".zip")) {
			ZipInputStream zipInputStream = new ZipInputStream(inputStream);
			if (zipInputStream.getNextEntry() == null) {
				throw new NullPointerException("文件解压异常，zipInputStream.getNextEntry is null。文件[" + accessOutObject.getRawAccessName() + "]");
			}
			reader = fac.createXMLStreamReader(zipInputStream);
		} else {
			reader = fac.createXMLStreamReader(inputStream);
		}
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		StringBuffer strBuff = new StringBuffer();
		this.dataRecordMap = new HashMap<String, String>();
		String tagName;
		String lastTagName = null;
		String listName = null;
		int type = -1;
		boolean flag = false;
		boolean subFlag = false;
		try {
			while (reader.hasNext()) {
				tagName = null;
				type = reader.next();
				// 只取开始和结束标签
				if (type != XMLStreamConstants.START_ELEMENT && type != XMLStreamConstants.END_ELEMENT)
					continue;
				tagName = reader.getLocalName();
				switch (type) {
					case XMLStreamConstants.START_ELEMENT : {
						/** 开始读入内容 */
						if (flag) {
							if ("list".equalsIgnoreCase(tagName)) {
								listName = StringUtil.nvl(reader.getAttributeValue(null, "name"), "");
								lastTagName = tagName;
								break;
							}
							if ("list".equalsIgnoreCase(lastTagName) && "item".equalsIgnoreCase(tagName)) {
								subFlag = true;
								break;
							}
							if (subFlag) {
								String name = StringUtil.nvl(reader.getAttributeValue(null, "name"), "").toUpperCase();
								// 针对不同listName相同name的处理，同步解析模板
								if (MultiListNames.contains(listName)) {
									name = (listName + "_" + name).toUpperCase();
								}
								strBuff.setLength(0);
								String val = null;
								if ((val = dataRecordMap.get(name)) == null) {
									strBuff.append(StringUtil.nvl(reader.getElementText(), ""));
								} else {
									strBuff.append(val).append("|").append(StringUtil.nvl(reader.getElementText(), ""));
								}
								dataRecordMap.put(name, strBuff.toString());
								break;
							}
							String name = StringUtil.nvl(reader.getAttributeValue(null, "name"), "");
							dataRecordMap.put(name.toUpperCase(), StringUtil.nvl(reader.getElementText(), ""));
							break;
						}
						if ("managedObject".equalsIgnoreCase(tagName)) {
							String className = StringUtil.nvl(reader.getAttributeValue(null, "class"), "");
							if (this.findMyTemplet(className)) {
								// 版本 区分TDD/FDD
								String version = StringUtil.nvl(reader.getAttributeValue(null, "version"), "");
								dataRecordMap.put("VERSION", version);

								String distName = StringUtil.nvl(reader.getAttributeValue(null, "distName"), "");
								if(StringUtil.isNotEmpty(distName)){
									dataRecordMap.put("DISTNAME", distName);
								String[] dn = StringUtil.split(distName, "/");
								if(null!= dn){
								int index = -1;
								for (int i = 0; i < dn.length; i++) {
									index = dn[i].indexOf("-");
									dataRecordMap.put(dn[i].substring(0, index).toUpperCase(), dn[i].substring(index + 1, dn[i].length()));
								}
								}
								}
								// 开关打开
								flag = true;
							}
						}
						break;
					}
					case XMLStreamConstants.END_ELEMENT : {
						if ("list".equalsIgnoreCase(lastTagName) && "item".equalsIgnoreCase(tagName)) {
							subFlag = false;
							break;
						}
						// 读取完毕，返回
						if ("managedObject".equalsIgnoreCase(tagName) && flag) {
							return true;
						}
						break;
					}
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
		List<Field> fieldList = this.templet.getFieldList();
		Map<String, String> map = this.createExportPropertyMap(this.templet.getDataType());
		for (Field field : fieldList) {
			if (field == null) {
				continue;
			}
			String value = dataRecordMap.get(field.getName().trim().toUpperCase());
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
		map.put("STAMPTIME", TimeUtil.getDateString(this.currentDataTime));

		if ("LNBTS".equalsIgnoreCase(this.templet.getDataName())) {
			String value = dataRecordMap.get("NAME");
			if (!StringUtil.isEmpty(value)) {
				map.put("ENBNAME", value);
			}
		} else if ("LNCEL".equalsIgnoreCase(this.templet.getDataName())) {
			String value = dataRecordMap.get("NAME");
			if (!StringUtil.isEmpty(value)) {
				map.put("CELLNAME", value);
			}
		}
		handleTime(map);
		record.setType(this.templet.getDataType());
		record.setRecord(map);
		dataRecordMap = null;
		return record;
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
	public final boolean findMyTemplet(String tagName) {
		this.templet = this.templetMap.get(tagName);// 这里的key全部转为大写字母
		if (this.templet == null) {
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
			String patternTime = StringUtil.getPattern(fileName, "\\d{8}");
			if (patternTime == null) {
				patternTime = StringUtil.getPattern(fileName, "\\d{4}[-]\\d{2}[-]\\d{2}");
			}
			if (patternTime != null) {
				patternTime = patternTime.replace("-", "");
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
		NokiaCmXmlTempletParser templetParser = new NokiaCmXmlTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();
		this.templetMap = templetParser.getTemplets();
	}
}
