package cn.uway.igp.lte.parser.cdt;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;
import cn.uway.util.Util;

public class ZteCoreNetCsvFileParser extends AbstractParser {

	private static Logger LOGGER = LoggerFactory
			.getLogger(ZteCoreNetCsvFileParser.class);

	private static final String FIELD_SPLIT_TAG = ",";

	private static final char LINE_SPLIT_TAG = (char) 10;

	protected static final int UDN_MM_ZTE_DATA_TYPE = 1001;

	protected LteCoreCommonDataManager lteCoreCommonDataManager;

	private long taskID;

	private StreamAccessOutObject streamOut;

	private BufferedLineReader reader = null;

	private Templet templet = null;

	private String filePath = null; // 要解析的文件名

	private String fileName = null;

	private String lineRecord = null;

	private long readLineNum = 0; // 记录总行数

	public Map<String, Templet> templetMap;

	private long invalid_cache_num = 0l;

	private SimpleDateFormat timeFormatPrecesionToMillSec = new SimpleDateFormat(
			"yyyy-MM-dd HH:mm:ss.SSS");

	private SimpleDateFormat timeFormatPrecesionToSec = new SimpleDateFormat(
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
		this.reader = new BufferedLineReader(new InputStreamReader(
				this.streamOut.getOutObject(), "GBK"), 16 * 1024);
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		if (null == reader) {
			return false;
		}

		while ((this.lineRecord = this.reader.readLine(LINE_SPLIT_TAG)) != null) {
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
		String[] strs = StringUtil.split(this.lineRecord, FIELD_SPLIT_TAG);
		Map<String, String> recordData = this
				.createExportPropertyMap(this.templet.dataType);
		// new HashMap<String,String>();
		List<Field> fieldList = this.templet.fieldList;
		Long START_TIME = null;
		// 样例文件中最后有一个逗号，如果正式文件中没有，这里的减一就去掉
		for (int i = 0; i < fieldList.size(); i++) {
			Field field = fieldList.get(i);
			if (i == 1) {
				if (null == strs[i] || "".equalsIgnoreCase(strs[i])) {
					continue;
				}
				String[] time = strs[i].split("\\.");
				if (time.length < 2) {
					continue;
				}

				// 2016-05-05 08:17:48.938
				START_TIME = dateToLong(strs[i]);
				recordData.put(fieldList.get(i).getIndex(),
						longToDate(START_TIME));

				continue;
			}

			if (null != field.getType()
					&& field.getType().equalsIgnoreCase("date")) {
				if (null == strs[i] || "".equalsIgnoreCase(strs[i])) {
					continue;
				}
				strs[i] = longToDate(dateToLong(strs[i]));
			}

			if (field.isHex()) {
				recordData.put(field.getIndex(), hexToDecimal(strs[i]));
			} else {
				recordData.put(field.getIndex(), strs[i]);
			}
		}
		
		//处理电话号码号
		String pStr = recordData.get("MSISDN");
		if(pStr!=null){
			pStr = getPhoneNum(pStr);
			recordData.put("MSISDN", pStr);
		}
		
		if (START_TIME != null
				&& UDN_MM_ZTE_DATA_TYPE == this.templet.getDataType()) {
			exportCommonData(currentDataTime, START_TIME, recordData);
		}

		readLineNum++;
		// recordData.put("OMCID",
		// String.valueOf(this.task.getExtraInfo().getOmcId()));
		// recordData.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		// recordData.put("STAMPTIME",
		// TimeUtil.getDateString(this.currentDataTime));
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
		String[] value = new String[9];
		value[0] = record.get("UE_MME");			//ueS1apID(mme侧)
		value[1] = record.get("OLDGUTI_MTMSI");		//tmsi			newguti_mtmsi
		value[2] = record.get("OLDGUTI_MMEGRPID");	//mmeGroupID	newguti_mmegrpid
		value[3] = record.get("OLDGUTI_MMECODE");	//mmeCode		newguti_mmecode
		value[4] = record.get("IMSI");
		value[5] = record.get("MSISDN");
		value[6] = record.get("NEWGUTI_MTMSI");
		value[7] = record.get("NEWGUTI_MMEGRPID");
		value[8] = record.get("NEWGUTI_MMECODE");
		
		if (value[0] != null && value[0].equals("0"))
			value[0] = null;
		if (value[1] != null && value[1].equals("0"))
			value[1] = null;
		if (value[6] != null && value[6].equals("0"))
			value[6] = null;
		
		try {
			int ret = LteCoreCommonDataManager.INVALID_RECORD_PARAM;
			if (value[0] != null && value[1] != null && value[1].length() > 0) {
				ret = lteCoreCommonDataManager.addCoreCommonData(fileTime,
						recordTime,
						LteCoreCommonDataManager.converToLong(value[0]),
						LteCoreCommonDataManager.converToLong(value[1]),
						LteCoreCommonDataManager.converToInteger(value[2]),
						LteCoreCommonDataManager.converToInteger(value[3]),
						LteCoreCommonDataManager.converToLong(value[4]), value[5],
						taskID);
			}
			
//			// new 的大部份为0, 如果不为0,也要加一次，作为关联条件
//			if (value[0] != null && value[6] != null && value[6].length()>0 && !value[6].equals("0")) {
//				if (lteCoreCommonDataManager.addCoreCommonData(fileTime,
//							recordTime,
//							null,
//							LteCoreCommonDataManager.converToLong(value[6]),
//							LteCoreCommonDataManager.converToInteger(value[7]),
//							LteCoreCommonDataManager.converToInteger(value[8]),
//							LteCoreCommonDataManager.converToLong(value[4]), value[5],
//							taskID) == LteCoreCommonDataManager.INVALID_RECORD_TIME) {
//					ret = LteCoreCommonDataManager.INVALID_RECORD_TIME;
//				}
//			}
			
			if (ret == LteCoreCommonDataManager.INVALID_RECORD_TIME)
				++invalid_cache_num;

		} catch (Exception e) {
			LOGGER.error("写入核心网数据到cache中异常", e);
		}
	}

	protected long dateToLong(String date) throws Exception {
		return timeFormatPrecesionToMillSec.parse(date).getTime();
	}

	protected String longToDate(long date) throws Exception {
		return timeFormatPrecesionToSec.format(new Date(date));
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
		HwCdtXMLTempletParser templetParser = new HwCdtXMLTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();
		templetMap = templetParser.getTemplets();
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
			// 文件名：MME_sgsnmme_mm_20160505075505.228.31.rcm2.dat.zip
			String patternTime = StringUtil.getPattern(this.fileName,
					"20\\d{12}");
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
