package cn.uway.igp.lte.parser.pm.nokia;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.igp.lte.parser.cm.LucCmXMLParser;
import cn.uway.igp.lte.templet.xml.NokiaPmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class NokiaPmXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(LucCmXMLParser.class);

	public static String myName = "诺西性能解析(类XML)";

	public String fileTime = null;

	// public Date stampTime;

	/** 版本 */
	public String version;

	/** 输入zip流 */
	public TarArchiveInputStream tarGzStream;

	public TarArchiveEntry entry = null;

	/** XML流 */
	public XMLStreamReader reader = null;

	/** 数据记录map */
	public Map<String, String> dataRecordMap = null;
	
	/**test file-stamptime*/
	public String stamptime="";

	public NokiaPmXMLParser() {
	}

	public NokiaPmXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.before();
		// 解析模板 获取当前文件对应的templet
		parseTemplet();
		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());
		XMLInputFactory fac = XMLInputFactory.newInstance();
		fac.setProperty("javax.xml.stream.supportDTD", false);
		this.tarGzStream = new TarArchiveInputStream(inputStream);
		this.entry = tarGzStream.getNextTarEntry();
		if (this.entry == null)
			new Exception("解析异常, 文件:" + accessOutObject.getRawAccessName());
		while (!this.entry.isFile()) {
			this.entry = tarGzStream.getNextTarEntry();
			if (this.entry == null)
				new Exception("解析异常, 文件:" + accessOutObject.getRawAccessName());
		}
		this.reader = fac.createXMLStreamReader(new GZIPInputStream(tarGzStream));
		this.parseFileNameGetFileTime(this.entry.getName());
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		String tagName = null;
		String lastTagName = null;
		List<String> dnValList = null;
		int type = -1;
		boolean flag = false;
		boolean subFlag = false;
		try {
			while (reader.hasNext()) {
				type = reader.next();
				// 只取开始和结束标签
				if (type != XMLStreamConstants.START_ELEMENT && type != XMLStreamConstants.END_ELEMENT)
					continue;
				tagName = reader.getLocalName();
				switch (type) {
					case XMLStreamConstants.START_ELEMENT : {
						/** 开始读入内容 */
						if (subFlag) {
							this.dataRecordMap.put(tagName.toUpperCase(), StringUtil.nvl(reader.getElementText(), ""));
							break;
						}
						if ("PMMOResult".equalsIgnoreCase(tagName) || flag) {
							if ("DN".equalsIgnoreCase(tagName)) {
								if (dnValList == null) {
									dnValList = new ArrayList<String>();
								}
								dnValList.add(StringUtil.nvl(reader.getElementText(), ""));
								break;
							} else if ("PMTarget".equalsIgnoreCase(tagName)) {
								String measurementTypeName = StringUtil.nvl(reader.getAttributeValue(null, "measurementType"), "");
								if (this.findMyTemplet(measurementTypeName)) {
									 this.dataRecordMap = new HashMap<String, String>();
									 dataRecordMap.put("STAMPTIME",stamptime);
									// 取值开关打开
									subFlag = true;
								} else {
									flag = false;
								}
								break;
							}
							if (!flag) {
								lastTagName = tagName;
								// 开关打开
								flag = true;
							}
						} else if ("PMSetup".equalsIgnoreCase(tagName)) {
							String timeStr = StringUtil.nvl(reader.getAttributeValue(null, "startTime"), "");
							stamptime=timeStr.replace('T', ' ').substring(0, (timeStr.indexOf("+") - 4));
							// this.dataRecordMap = new HashMap<String, String>();
							// dataRecordMap.put("STAMPTIME",timeStr.replace('T', ' ').substring(0, (timeStr.indexOf("+") - 4)));
							
						}
						break;
					}
					case XMLStreamConstants.END_ELEMENT : {
						if (tagName.equalsIgnoreCase(lastTagName)) {
							// 读取完毕，返回
							if (flag) {
								for (String dnVal : dnValList) {
									// DN 信息解析
									String[] strs = StringUtil.split(dnVal, "/");
									int index = -1;
									for (int i = 0; i < strs.length; i++) {
										index = strs[i].indexOf("-");
										String name = strs[i].substring(0, index).toUpperCase();
										/*
										 * L_PM_NK_8017/L_PM_NK_8015/L_PM_NK_8019 是小区与邻区的数据,属于一对多的关系 已L_PM_NK_8017为例:
										 * <DN><![CDATA[PLMN-PLMN/MRBTS-711/LNBTS-711/LNCEL-113]]></DN> <DN><![CDATA[PLMN-PLMN/MCC-244/MNC-08]]></DN>
										 * <DN><![CDATA[PLMN-PLMN/HOT-182/TCID-1]]></DN> <DN><![CDATA[PLMN-PLMN/MCC-262/MNC-03]]></DN> 存在4条DN数据,那入库字段为
										 * MRBTS,LNBTS,LNCEL,MCC,MNC,HOT,TCID,DMCC,DMNC 第4行MCC,MNC 与第2行字段重复,故入库的时候加个D,来区分不同小区的MCC,MNC
										 */
										if (this.dataRecordMap.get(name) != null) {
											this.dataRecordMap.put("D" + name, strs[i].substring(index + 1, strs[i].length()));
										} else {
											this.dataRecordMap.put(name, strs[i].substring(index + 1, strs[i].length()));
										}
									}
								}
								dnValList = null;
								return true;
							}
							//不能删除
							dnValList = null;
						}
						break;
					}
				}
			}
			while ((this.entry = tarGzStream.getNextTarEntry()) != null && this.entry.isFile()) {
				this.parseFileNameGetFileTime(entry.getName());
				XMLInputFactory fac = XMLInputFactory.newInstance();
				fac.setProperty("javax.xml.stream.supportDTD", false);
				this.reader = fac.createXMLStreamReader(new GZIPInputStream(tarGzStream));
				return hasNextRecord();
			}
		} catch (XMLStreamException e) {
			this.cause = "【" + myName + "】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		Map<String, String> map = this.createExportPropertyMap(this.templet.getDataType());
		List<Field> fieldList = this.templet.getFieldList();
		for (Field field : fieldList) {
			if (field == null) {
				continue;
			}
			String value = dataRecordMap.get(field.getName().trim().toUpperCase());
			// 找不到，设置为空
			if (value == null) {
				continue;
			}
			map.put(field.getIndex(), value);
		}
		// 公共回填字段
		map.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		//关闭回填,使用文件里的厂家starttime作stamptime
		//map.put("STAMPTIME", TimeUtil.getDateString(this.currentDataTime));
		map.put("FILE_TIME", this.fileTime);
		ParseOutRecord record = new ParseOutRecord();
		record.setType(this.templet.getDataType());
		record.setRecord(map);
		readLineNum++;
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
			// LOGGER.debug("没有找到对应的模板，跳过。measurementType:{}、fileName:{}", new Object[]{tagName, this.entry.getName()});
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
			String patternTime = StringUtil.getPattern(fileName, "\\d{8}[.]\\d{4}");
			if (patternTime == null) {
				patternTime = StringUtil.getPattern(fileName, "\\d{12}");
			}
			if (patternTime != null) {
				patternTime = patternTime.replace(".", "");
				this.currentDataTime = TimeUtil.getDate(patternTime, "yyyyMMddHHmm");
				return;
			}
			if (patternTime == null) {
				patternTime = StringUtil.getPattern(fileName, "\\d{10}");
			}
			if (patternTime != null) {
				this.currentDataTime = TimeUtil.getDate(patternTime, "yyyyMMddHH");
			}
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	/**
	 * 解析文件名上的file_time
	 * 
	 * @throws Exception
	 */
	public void parseFileNameGetFileTime(String fileName) {
		try {
			// 文件名样例：etlexpmx_LNCEL_20140716110602_1094084.xml.gz
			this.fileTime = fileName.substring(fileName.lastIndexOf("_") + 1, fileName.indexOf(".xml"));
			if(this.fileTime.equals("1208729"))
				System.err.println("444444%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
			
			/**
			 * <pre>
			 * 暂时不加上去.
			String[] fileKeys = fileTime.split("\\_");
			for (String fileKey : fileKeys) {
				if (fileKey.length()>=8 && fileKey.startsWith("20") && org.apache.commons.lang.math.NumberUtils.isNumber(fileKey)) {
					this.fileTime = fileKey;
					return;
				}
			} </pre>
			*/
		} catch (Exception e) {
			LOGGER.debug("解析文件名上的file_time异常，fileName={}。", fileName, e);
			this.fileTime = null;
		}
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		// 解析模板
		NokiaPmXmlTempletParser templetParser = new NokiaPmXmlTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();
		this.templetMap = templetParser.getTemplets();
	}
}
