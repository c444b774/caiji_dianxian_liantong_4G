package cn.uway.igp.lte.parser.pm.hw;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.igp.lte.templet.xml.HwPmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;
import cn.uway.util.Util;

public class HwPmZipXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(HwPmZipXMLParser.class);

	/**
	 * 压缩文件名
	 */
	public String zipFileName;

	/** 输入zip流 */
	public ZipInputStream zipstream;

	/**
	 * 解压缩文件对象
	 */
	public ZipEntry entry = null;

	/**
	 * 数据开始时间
	 */
	public String beginTime = null;

	public static final String BEGIN_TIME_TAG = "<measCollec beginTime=\"";

	public static final int BEGIN_TIME_TAG_LEN = BEGIN_TIME_TAG.length();

	/**
	 * 数据类型标识
	 */
	public String className = null;

	public static final String CLASS_NAME_TAG = "<measInfo measInfoId=\"";

	public static final int CLASS_NAME_TAG_LEN = CLASS_NAME_TAG.length();

	/**
	 * 数据类型结束标识
	 */
	public static final String CLASS_NAME_END_TAG = "</measInfo>";

	/**
	 * 列头字段名称信息
	 */
	public String[] fields = null;

	public static final String FIELD_NAME_TAG = "<measTypes>";

	public static final int FIELD_NAME_TAG_LEN = FIELD_NAME_TAG.length();

	/**
	 * measObjLdn字段信息
	 */
	public String measObjLdn = null;

	public static final String MEAS_OBJ_LDN_TAG = "<measValue measObjLdn=\"";

	public static final int MEAS_OBJ_LDN_TAG_LEN = MEAS_OBJ_LDN_TAG.length();

	/**
	 * 解析字段内容值
	 */
	public String[] fieldValues = null;

	public static final String FIELD_VALUE_TAG = "<measResults>";

	public static final int FIELD_VALUE_TAG_LEN = FIELD_VALUE_TAG.length();

	/**
	 * 一条数据的结束标记
	 */
	public static final String LINE_RECORD_END_TAG = "</measValue>";

	/**
	 * <数据类型，模板配置解析字段>
	 */
	public HashMap<String, ParseTemplet> tempFieldMap = new HashMap<String, ParseTemplet>();

	/**
	 * <解析字段名，模板解析字段信息>
	 */
	public ParseTemplet parseTemplet = null;

	public HwPmZipXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	public HwPmZipXMLParser() {
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.zipFileName = this.accessOutObject.getRawAccessName();// 压缩文件
		this.before();
		LOGGER.debug("开始解码:{}", this.rawName);
		// 解析模板 获取当前文件对应的templet
		this.parseTemplet();
		if (this.templetMap.isEmpty()) {
			LOGGER.warn("华为性能xml文件未读取到解析模板信息。");
			return;
		}
		this.zipstream = new ZipInputStream(this.inputStream);
		// 解析压缩包
		this.parserZipStream();
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		while (!this.readRecord()) {
			if (!this.parserZipStream()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 解析读取数据记录
	 * 
	 * @return
	 */
	public final boolean readRecord() {
		if (null == this.reader) {
			return false;
		}
		int startIndex = -1;
		String lineRecord = null;
		try {
			while ((lineRecord = this.reader.readLine()) != null) {
				lineRecord = lineRecord.trim();
				// measObjLdn字段信息
				startIndex = lineRecord.indexOf(MEAS_OBJ_LDN_TAG);
				if (startIndex != -1) {
					this.measObjLdn = lineRecord.substring(MEAS_OBJ_LDN_TAG_LEN, lineRecord.indexOf("\">")).trim();
					continue;
				}
				// 解析字段内容值
				startIndex = lineRecord.indexOf(FIELD_VALUE_TAG);
				if (startIndex != -1) {
					String fieldValueStr = lineRecord.substring(FIELD_VALUE_TAG_LEN, lineRecord.indexOf("</")).trim();
					this.fieldValues = StringUtil.split(fieldValueStr, " ");
					continue;
				}
				// 读取一条数据的结束标记
				if (lineRecord.indexOf(LINE_RECORD_END_TAG) != -1) {
					return true;
				}
				// 解析数据类型
				startIndex = lineRecord.indexOf(CLASS_NAME_TAG);
				if (startIndex != -1) {
					this.className = lineRecord.substring(CLASS_NAME_TAG_LEN, lineRecord.indexOf("\">"));
					continue;
				}
				// 解析列头字段名称
				startIndex = lineRecord.indexOf(FIELD_NAME_TAG);
				if (startIndex != -1) {
					String fieldHeadStr = lineRecord.substring(FIELD_NAME_TAG_LEN, lineRecord.indexOf("</")).trim().toUpperCase();
					this.fields = StringUtil.split(fieldHeadStr, " ");
					continue;
				}
				// 解析数据开始时间
				startIndex = lineRecord.indexOf(BEGIN_TIME_TAG);
				if (startIndex != -1) {
					int endIndex = lineRecord.indexOf("+");
					if (endIndex == -1)
						endIndex = lineRecord.lastIndexOf("-");
					this.beginTime = lineRecord.substring(BEGIN_TIME_TAG_LEN, endIndex).replace('T', ' ');
					continue;
				}
				// 读取类型数据结束标记，清空读取信息
				if (lineRecord.indexOf(CLASS_NAME_END_TAG) != -1) {
					this.className = null;
					this.fields = null;
					this.measObjLdn = null;
					this.fieldValues = null;
				}
			}
		} catch (Exception e) {
			LOGGER.error("华为性能xml文件读取压缩流异常。文件名：{}、压缩文件：{}、异常信息：{}", new Object[]{this.rawName, this.zipFileName, e.getMessage()});
		}
		return false;
	}

	/**
	 * 解析压缩包
	 * 
	 * @return
	 * @throws Exception
	 */
	public final boolean parserZipStream() throws Exception {
		try {
			this.entry = this.zipstream.getNextEntry();
			if (this.entry == null) {
				return false;
			}
			this.rawName = this.entry.getName();
			// 解析文件名
			this.parseFileName();
			this.beginTime = null;
			// 转换为缓冲流读取
			this.reader = new BufferedReader(new InputStreamReader(this.zipstream, "GBK"), 16 * 1024);
		} catch (Exception e) {
			LOGGER.error("华为性能xml文件读取压缩流异常。文件名：{}、压缩文件：{}、异常信息：{}", new Object[]{this.rawName, this.zipFileName, e.getMessage()});
			this.reader = null;
			return false;
		}
		//LOGGER.debug("开始解码:{}", this.rawName);
		return true;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		// 查找解析模板
		if (!this.findMyTemplet()) {
			this.measObjLdn = null;
			this.fieldValues = null;
			this.invalideNum++;
			return null;
		}
		
		int nOMCID = task.getExtraInfo().getOmcId();
		Map<String, String> recordData = this.createExportPropertyMap(this.parseTemplet.dataType);
		HashMap<String, Field> fieldsInfo = this.parseTemplet.fieldsInfo;
		try {
			for (int i = 0; i < this.fields.length; i++) {
				Field field = fieldsInfo.get(this.fields[i]);
				if (field == null) {
					continue;
				}
				String value = this.fieldValues[i].trim();
				if ("".equals(value) || "NIL".equalsIgnoreCase(value)) {
					continue;
				}
				recordData.put(field.getIndex(), value);
				// 是否拆封字段 TODO
				// if ("true".equals(field.getIsSplit())) {
				// }
			}
			
			recordData.put("MEASOBJLDN", this.measObjLdn);
			
			// FDN 拆封
			Field field = fieldsInfo.get("MEASOBJLDN");
			if (field != null) {
				if (!this.splitMeasObjLdnStr(this.measObjLdn, field, recordData)) {
					LOGGER.warn("华为性能xml文件解析字段拆封失败。。measObjLdn：{}、文件名：{}、压缩文件：{}", new Object[]{this.measObjLdn, this.rawName,
							this.zipFileName});
					this.invalideNum++;
					return null;
				}
			}
			
			// TODO:对以下四张表MEASOBJLDN字段进行压缩处理，主要是为了解决太少，占空间，需求只要值唯一(临时)
			if (this.parseTemplet.dataType == 4008
					|| this.parseTemplet.dataType == 4067
					|| this.parseTemplet.dataType == 4068
					|| this.parseTemplet.dataType == 4094) {
				String srcMeasobjldn = this.measObjLdn;
				if (srcMeasobjldn != null && srcMeasobjldn.length() > 32) {
					String compressCode = Util.toMD5(srcMeasobjldn);
					String subCode = Util.toMD5(srcMeasobjldn + nOMCID);
					// 取原来值的MD5码+和taskID的MD5码前16位作掩码，让撞码的概率降低到忽略不计
					recordData.put("MEASOBJLDN", compressCode + subCode.substring(0, 16));
				}
			}
		} catch (Exception e) {
			LOGGER.error("华为性能xml文件解析异常。文件名：{}、压缩文件：{}、异常信息：{}", new Object[]{this.rawName, this.zipFileName, e.getMessage()});
			this.invalideNum++;
			return null;
		}
		// 公共回填字段
		recordData.put("OBJECTNO", "0");
		recordData.put("MMEID", String.valueOf(nOMCID));
		recordData.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		recordData.put("STARTTIME", TimeUtil.getDateString(this.currentDataTime));
		recordData.put("STAMPTIME", this.beginTime);
		ParseOutRecord outRecord = new ParseOutRecord();
		outRecord.setType(this.parseTemplet.dataType);
		outRecord.setRecord(recordData);
		readLineNum++;
		return outRecord;
	}

	/**
	 * FDN 拆封
	 * 
	 * @param value
	 * @param field
	 * @param recordData
	 */
	public final boolean splitMeasObjLdnStr(String value, Field field, Map<String, String> recordData) {
		// "L昌北区财大第五教学楼-XY/GTPU:单板类型=MPT, 柜号=0, 框号=0, 槽号=7" or
		// "L昌北区财大第五教学楼-XY/小区:eNodeB名称=L昌北区财大第五教学楼-XY, 本地小区标识=1, 小区名称=L昌北区财大第五教学楼-XY-1, eNodeB标识=553390, 小区双工模式=CELL_FDD" or ...
		try {
			String[] strs = value.split("/");
			if (strs.length < 2) {
				return false;
			}
			
			/**
			 * 20170213 dpf add 带有"小区名称=L昌北区财大第五教学楼7F/11-12F"的measObjLdn以“/”拆分时导致数据不完整并有部分属性丢失
			 * */
			strs[0] = value.substring(0, value.indexOf("/"));
			strs[1] = value.substring(value.indexOf("/") + 1);
			
			String[] values = StringUtil.split(strs[1], ", ");
			// 分拆字段列表
			List<Field> fieldList = field.getSubFieldList();
			recordData.put(fieldList.get(0).getIndex(), strs[0]);
			for (Field subField : fieldList) {
				for (int i = 0; i < values.length; i++) {
					int index = values[i].indexOf("=");
					if (index < 1) {
						continue;
					}
					String name = values[i].substring(0, index);
					if (!name.equalsIgnoreCase(subField.getName())) {
						continue;
					}
					String val = values[i].substring(index + 1, values[i].length());
					recordData.put(subField.getIndex(), val);
					break;
				}
			}
		} catch (Exception e) {
			LOGGER.error("", e);
			return false;
		}
		return true;
	}

	/**
	 * 找到当前对应的Templet
	 */
	public final boolean findMyTemplet() {
		this.parseTemplet = this.tempFieldMap.get(this.className);
		if (this.parseTemplet != null) {
			return true;
		}
		this.templet = this.templetMap.get(this.className);
		if (this.templet == null) {
			//LOGGER.error("华为性能xml文件读取未找到模板信息，跳过数据类型。className：{}、文件名：{}、压缩文件：{}", new Object[]{this.className, this.rawName, this.zipFileName});
			return false;
		}
		this.parseTemplet = new ParseTemplet();
		this.parseTemplet.dataType = this.templet.dataType;
		for (Field field : this.templet.fieldList) {
			if (field == null) {
				continue;
			}
			this.parseTemplet.fieldsInfo.put(field.getName(), field);
		}
		this.tempFieldMap.put(this.className, this.parseTemplet);
		return true;
	}

	/**
	 * 解析文件名
	 * 
	 * @throws Exception
	 */
	public void parseFileName() {
		if (this.rawName == null) {
			LOGGER.warn("解析文件名异常。rawName={}", this.rawName);
			return;
		}
		try {
			// 文件名：A20140504.0000+0800-0100+0800_L昌北区财大第五教学楼-XY
			String fileName = FileUtil.getFileName(this.rawName);
			String patternTime = StringUtil.getPattern(fileName, "\\d{8}[.]\\d{4}");
			this.currentDataTime = TimeUtil.getDate(patternTime, "yyyyMMdd.HHmm");
		} catch (Exception e) {
			LOGGER.warn("{}文件名解析异常。{}", new Object[]{this.rawName, e.getMessage()});
		}
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("华为性能xml文件处理{}条记录", readLineNum);
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		// 解析模板
		HwPmXmlTempletParser hwPmXmlTempletParser = new HwPmXmlTempletParser();
		hwPmXmlTempletParser.tempfilepath = templates;
		hwPmXmlTempletParser.parseTemp();
		templetMap = hwPmXmlTempletParser.getTemplets();
	}

	class ParseTemplet {

		public int dataType = -100;

		/**
		 * <解析字段名，模板解析字段信息>
		 */
		public HashMap<String, Field> fieldsInfo = new HashMap<String, Field>();;
	}
}