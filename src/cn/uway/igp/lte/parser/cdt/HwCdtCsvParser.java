package cn.uway.igp.lte.parser.cdt;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.accessor.StreamAccessOutObject;
import cn.uway.framework.parser.AbstractParser;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.igp.lte.service.LteCoreCommonDataManager;
import cn.uway.igp.lte.templet.xml.HwCdtXMLTempletParser;
import cn.uway.util.TimeUtil;
import cn.uway.util.Util;

public class HwCdtCsvParser extends AbstractParser {

	private static Logger LOGGER = LoggerFactory
			.getLogger(HwCdtCsvParser.class);

	public static final String FIELD_SPLIT_TAG = ",";

	public static final char FIELD_SPLIT_CHAR_TAG = ',';

	public static final char LINE_SPLIT_TAG = (char) 10;

	protected static final int UDN_MM_HW_DATA_TYPE = 39001;

	protected LteCoreCommonDataManager lteCoreCommonDataManager;

	private long taskID;

	public StreamAccessOutObject streamOut;

	public BufferedLineReader reader = null;

	public Templet templet = null;

	public String filePath = null; // 要解析的文件名

	public String fileName = null;

	public String lineRecord = null;

	public long readLineNum = 0; // 记录总行数

	// 模板map<String/*file*/,String/*Templet*/>
	public Map<String, Templet> templetMap;

	private int start_line = 0;

	private long invalid_cache_num = 0l;

	private SimpleDateFormat timeFormat_eng = new SimpleDateFormat(
			"yyyy-MMM-dd HH:mm:ss", Locale.ENGLISH);

	private SimpleDateFormat timeFormat_std = new SimpleDateFormat(
			"yyyy-MM-dd HH:mm:ss");

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		if (accessOutObject == null)
			throw new IOException("接入对象无效，null.");

		this.streamOut = (StreamAccessOutObject) accessOutObject;
		this.task = this.streamOut.getTask();
		this.taskID = task.getId();

		this.filePath = this.streamOut.getRawAccessName();
		this.fileName = FilenameUtils.getBaseName(this.streamOut
				.getRawAccessName());
		this.startTime = new Timestamp(System.currentTimeMillis()); // 开始解析的时间。
		this.start_line = 0;
		// 解析模板 获取当前文件对应的templet
		if (!this.createTempletParser()) {
			LOGGER.debug("文件没有找到模板，不进行采集，直接返回:{}", filePath);
			return;
		}
		this.parseFileName();
		this.invalid_cache_num = 0l;

		if (this.lteCoreCommonDataManager == null)
			lteCoreCommonDataManager = LteCoreCommonDataManager
					.getWriteInstance();

		LOGGER.debug("开始解码:{} lteCoreCommonDataManager is:{}", new Object[]{
				filePath,
				(lteCoreCommonDataManager.isEnableState()
						? "enable"
						: "disable")});
		if (this.filePath.endsWith("gz")) {
			this.reader = new BufferedLineReader(new InputStreamReader(
					new GZIPInputStream(this.streamOut.getOutObject()), "GBK"),
					16 * 1024);
		} else {
			this.reader = new BufferedLineReader(new InputStreamReader(
					this.streamOut.getOutObject(), "GBK"), 16 * 1024);
		}
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		if (null == reader) {
			return false;
		}
		while ((this.lineRecord = this.reader.readLine(LINE_SPLIT_TAG)) != null) {
			// 跳过表头
			if (start_line == 0) {
				++start_line;
				continue;
			}
			
			++start_line;
			return true;
		}

		return false;
	}

	/**
	 * 处理Record数据
	 * @return
	 * @throws Exception
	 */
	protected Map<String, String> handleNextRecord() throws Exception {
		// 拆封字段
		// String[] strs = StringUtil.split(this.lineRecord, FIELD_SPLIT_TAG);

		Map<String, String> recordData = this
				.createExportPropertyMap(this.templet.dataType);
		/*
		 * 湖北核心网话单入impala，湖北核心网话单没有城市ID，由于系统公用的输出ParquetExoprter必须CITY_ID,
		 * 在此写死一个   author:huzq
		 * */
		recordData.put("CITY_ID", "0");
		List<Field> fieldList = this.templet.fieldList;
		Long PROCEDURE_START_TIME = null;
		// 样例文件中最后有一个逗号，如果正式文件中没有，这里的减一就去掉
		int prevTagPos = 0;
		int currTagPos = 0;
		int lineLength = lineRecord.length();
		int i = -1;
		try {
			while (prevTagPos < lineLength) {
				currTagPos = lineRecord.indexOf(FIELD_SPLIT_CHAR_TAG, prevTagPos);
				if (currTagPos < 0) {
					currTagPos = lineLength;
				}
	
				String fieldValue = lineRecord.substring(prevTagPos, currTagPos);
				prevTagPos = currTagPos + 1;
				++i;
	
				if (null == fieldValue || fieldValue.length() < 1)
					continue;
	
				Field field = fieldList.get(i);
				if (null != field.getType()
						&& field.getType().equalsIgnoreCase("date")) {
	
					long fieldDateValue = dateToLong(fieldValue);
					String timeValue = longToDate(fieldDateValue);
	
					if (i == 0) {
						// teime Simple: "2015-Mar-30 08:35:25 270"
						int nMillSecPos = fieldValue.lastIndexOf(' ',
								fieldValue.length());
						if (nMillSecPos > 0) {
							String timeMillSec = fieldValue
									.substring(nMillSecPos + 1);
							recordData.put("PROCEDURE_START_TIME_MS", timeMillSec);
							PROCEDURE_START_TIME = fieldDateValue;
						}
					}
					recordData.put(field.getIndex(), timeValue);
				} else if (field.isHex()) {
					recordData.put(field.getIndex(), hexToDecimal(fieldValue));
				} else {
					recordData.put(field.getIndex(), fieldValue);
				}
			}
		} catch (Exception e) {
			LOGGER.error("文件:{} 处理第{}行，第{}列出现异常:{}", new Object[] {this.fileName, start_line, i+1, e.getMessage()});
			LOGGER.error("错识内容：{}", lineRecord);
			return null;
		}
		//处理电话号码号
		String pStr = recordData.get("MSISDN");
		if(pStr!=null){
			pStr = getPhoneNum(pStr);
			recordData.put("MSISDN", pStr);
		}
		
		// recordData.put("OMCID",
		// String.valueOf(this.task.getExtraInfo().getOmcId()));
		// recordData.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		// recordData.put("STAMPTIME",
		// TimeUtil.getDateString(this.currentDataTime));

		if (PROCEDURE_START_TIME != null
				&& UDN_MM_HW_DATA_TYPE == this.templet.getDataType()) {
			exportCommonData(currentDataTime, PROCEDURE_START_TIME, recordData);
		}
		readLineNum++;
		return recordData;
	}
	
	@Override
	public ParseOutRecord nextRecord() throws Exception {
		Map<String, String> recordData = handleNextRecord();
		ParseOutRecord record = new ParseOutRecord();
		record.setType(this.templet.getDataType());
		record.setRecord(recordData);
		return record;
	}

	protected void exportCommonData(Date fileTime, long recordTime,
			Map<String, String> record) {
		String[] value = new String[6];
		value[0] = record.get("MME_UE_S1AP_ID");
		value[1] = record.get("NEW_GUTI_MTMSI");
		value[2] = record.get("NEW_GUTI_MMEGI");
		value[3] = record.get("NEW_GUTI_MMEC");
		value[4] = record.get("IMSI");
		value[5] = record.get("MSISDN");

		/*
		 * String ENB_ID = record.get("ENB_ID"); String ENODB_UES1AP_ID =
		 * record.get("ENODB_UES1AP_ID");
		 * 
		 * if (StringUtil.isEmpty(ENB_ID) || StringUtil.isEmpty(ENODB_UES1AP_ID)
		 * || (StringUtil.isEmpty(value[0]) && StringUtil.isEmpty(value[1]))) {
		 * 
		 * return; }
		 */

		try {
			int ret = lteCoreCommonDataManager.addCoreCommonData(fileTime,
					recordTime,
					LteCoreCommonDataManager.converToLong(value[0]),
					LteCoreCommonDataManager.converToLong(value[1]),
					LteCoreCommonDataManager.converToInteger(value[2]),
					LteCoreCommonDataManager.converToInteger(value[3]),
					LteCoreCommonDataManager.converToLong(value[4]), value[5],
					taskID);
			if (ret == -2)
				++invalid_cache_num;

		} catch (Exception e) {
			LOGGER.error("写入核心网数据到cache中异常", e);
		}
	}

	protected long dateToLong(String date) throws Exception {
		// SimpleDateFormat sdf = new SimpleDateFormat(regex, Locale.ENGLISH);
		return timeFormat_eng.parse(date).getTime();
	}

	protected String longToDate(long date) throws Exception {
		// SimpleDateFormat sdf = new SimpleDateFormat(regex);
		return timeFormat_std.format(new Date(date));
	}

	public void close() {
		lteCoreCommonDataManager.endWrite(taskID, this.currentDataTime);
		// 标记解析结束时间
		this.endTime = new Date();
		if (null != reader) {
			try {
				reader.close();
			} catch (IOException e) {
				LOGGER.error("[{}]-核心网话单解析，关闭文件输入流失败", e);
			}
		}
		LOGGER.debug("[{}]-核心网话单解析，处理{}条记录, 未加入成功的cache条数{}条", new Object[]{
				task.getId(), readLineNum, invalid_cache_num});
	}

	private boolean createTempletParser() throws Exception {
		// 解析模板
		if (templetMap == null || templetMap.size() < 1) {
			HwCdtXMLTempletParser templetParser = new HwCdtXMLTempletParser();
			templetParser.tempfilepath = templates;
			templetParser.parseTemp();
			templetMap = templetParser.getTemplets();
		}

		templet = findTempletByFileName(fileName);
		if (null == templet) {
			return false;
		}
		return true;
	}

	/**
	 * 找出解析此文件需要依赖的Templet对象
	 * 
	 * @return
	 */
	private Templet findTempletByFileName(String localFilename)
			throws Exception {
		// 取不包含路径的文件名
		Map<String, Templet> templets = templetMap;
		Set<String> fileNames = templets.keySet();
		File localFile = new File(localFilename);
		for (String fName : fileNames) {
			if (fName != null && fName.contains(">")) {
				if (FilenameUtils.getName(localFilename).equalsIgnoreCase(
						fName.subSequence(fName.indexOf(">") + 1,
								fName.length()).toString())) {
					return templets.get(fName);
				}
			} else if (this.streamOut.getRawAccessName().equalsIgnoreCase(
					fName.trim())
					|| logicEquals(
							localFile.getName(),
							Util.ParseFilePath(fName.trim(),
									this.task.getDataTime()))
					|| FilenameUtils.wildcardMatch(
							FilenameUtils.getName(localFilename),
							Util.ParseFilePath(fName.trim(),
									this.task.getDataTime()))
					|| Util.ParseFilePath(fName.trim(), this.task.getDataTime())
							.endsWith(FilenameUtils.getName(localFilename))) {
				return templets.get(fName);
			} else if (fName.trim().indexOf("*") > -1) {
				if (FilenameUtils.wildcardMatch(FilenameUtils
						.getName(localFilename), Util.ParseFilePath(
						FilenameUtils.getName(fName.trim()),
						this.task.getDataTime()))) {
					// if (wildCardMatch(ConstDef.ParseFilePath(fName.trim(),
					// collectObjInfo.getLastCollectTime()), localFilename,
					// "*")) {
					return templets.get(fName);
				}
			}
		}
		return null;
	}

	private boolean logicEquals(final String shortFileName,
			final String fileName) {
		// 不包含通配符的情况下，当作普通的String.equals()方法处理
		if (!fileName.contains("*") && !fileName.contains("?")) {
			return shortFileName.equals(fileName);
		}

		String s1 = shortFileName.replaceAll("\\.", ""); // 把.号去掉，因为它在正则表达式中有意义。
		String s2 = fileName.replaceAll("\\.", ""); // 把.号去掉，因为它在正则表达式中有意义。
		s1 = s1.replaceAll("\\+", "");
		s2 = s2.replaceAll("\\+", "");
		s2 = s2.replaceAll("\\*", ".*"); // *换成.*，表示多匹配多个字符
		s2 = s2.replaceAll("\\?", "."); // ?换成.，表示匹配单个字符

		return Pattern.matches(s2, s1); // 通过正则表达式方式判断
	}

	/**
	 * 解析文件名
	 * 
	 * @throws Exception
	 */
	private void parseFileName() {
		if (this.fileName == null) {
			LOGGER.warn("解析文件名异常。rawFileName={}", this.fileName);
			return;
		}
		try {
			// 文件名：HuaweiUDN-MM01_20141218100043_000000.csv.gz
			String patternTime = fileName.split("_")[1];
			this.currentDataTime = TimeUtil.getDate(patternTime,
					"yyyyMMddHHmmss");
		} catch (Exception e) {
			LOGGER.warn("{}文件名解析异常。{}",
					new Object[]{this.fileName, e.getMessage()});
		}
	}

	protected static String hexToDecimal(String value) {
		if (null == value || "".equalsIgnoreCase(value)) {
			return null;
		}
		if (value.contains("0x")) {
			value = value.substring(2, value.length());
		}
		return String.valueOf(Long.valueOf(value, 16));
	}

	@Override
	public List<ParseOutRecord> getAllRecords() {
		return null;
	}

	@Override
	public Date getDataTime(ParseOutRecord outRecord) {
		return this.currentDataTime;
	}

	public static void main(String[] args) throws ParseException {
		String fileName = "HuaweiUDN-MM01_20150506055049_000000.csv.gz";
		String patternTime = fileName.split("_")[1];
		Date currentDataTime = TimeUtil.getDate(patternTime, "yyyyMMddhhmmss");
		System.out.println(TimeUtil.getDateString(currentDataTime));
	}
}
