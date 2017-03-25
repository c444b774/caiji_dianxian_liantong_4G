package cn.uway.igp.lte.parser.cdt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.accessor.StreamAccessOutObject;
import cn.uway.framework.context.AppContext;
import cn.uway.framework.context.Vendor;
import cn.uway.framework.job.AdaptiveInputStream;
import cn.uway.framework.job.AdaptiveInputStream.CompressionFileEntry;
import cn.uway.framework.orientation.OneWayDelayDist;
import cn.uway.framework.orientation.OrientationAPI;
import cn.uway.framework.orientation.Type.DEV_TYPE;
import cn.uway.framework.orientation.Type.DiffuseInfo;
import cn.uway.framework.orientation.Type.LONG_LAT;
import cn.uway.framework.orientation.Type.ONEWAYDELAY_CELL;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.CSVCfcTempletParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.framework.task.worker.TaskWorkTerminateException;
import cn.uway.igp.lte.parser.cdt.HwCdtCsvPublicParser.CellStrengthComparator;
import cn.uway.igp.lte.service.AbsImsiQuerySession.ImsiRequestResult;
import cn.uway.igp.lte.service.ImsiQueryHelper;
import cn.uway.igp.lte.service.LteCoreCommonDataManager;
import cn.uway.igp.lte.util.AssociateCellUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TempMD5;
import cn.uway.util.TimeUtil;

public class ZteCdtCsvPublicParser extends FileParser implements ImsiQueryHelper.ASynchronizedParser {

	private static Logger LOGGER = LoggerFactory.getLogger(ZteCdtCsvPublicParser.class);

	//private static final Logger badWriter = BadWriter.getInstance().getBadWriter();

	public StreamAccessOutObject streamOut;

	public String rawFilePath = null; // 要解析的文件
	
	protected int nMinOrientNeiCellsNumber = 0;
	
	public String rawFileName;

	public BufferedReader reader = null;

	public String[] cdrHeadInfo = null;
	public String[] cdrHeadFieldIndex = null;

	public HashMap<String, String> cdrFieldNames = null;

	public String lineRecord = null;

	public long readLineNum = 0; // 记录总行数

	// 保留小数点后5位

	protected ImsiQueryHelper imsiQueryHelper;
	
	protected Long curr_enb_id;
	
	protected String[] lineValues = new String[2048];
	
    public DecimalFormat df = new DecimalFormat("0.00000");
	
	public DecimalFormat d2f = new DecimalFormat("0.00");
	public DecimalFormat d6f = new DecimalFormat("0.000000");

	/**
	 * 原始文件粒度
	 */
	protected final static long RAWFILE_UNIT_TIMESEC = 5*60*1000l;
	
	/**
	 * 数据时间和文件时间的有效区间
	 */
	private static final long timeValidRange = 365*24 * 60 * 60 * 1000L;
		
	/**
	 * 华为数据格式(20160320215032)，SimpleDateFormat不要定义成静态变量的,not synchronized.
	 * Date formats are not synchronized. It is recommended to create separate format instances for each thread. If multiple threads access a format concurrently, it must be synchronized externally.
	 */
	private final SimpleDateFormat cdrTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	
	private final SimpleDateFormat cdrFileTimeFormat = new SimpleDateFormat("yyyyMMddHHmmss");
	
	private AdaptiveInputStream adaptiveStream = null;
	
	private static final String[] timeFields = {"CDR_STARTTIME","CDR_ENDTIME","AC_RRCSTARTTIME","AC_RRCENDTIME","ERAB_CREATETIME1",
			"ERAB_CREATETIME2","ERAB_CREATETIME3","ERAB_CREATETIME4","ERAB_CREATETIME5","ERAB_CREATETIME6","ERAB_CREATETIME7",
			"ERAB_CREATETIME8","ERAB_DEACTIVATETIME1","ERAB_DEACTIVATETIME2","ERAB_DEACTIVATETIME3","ERAB_DEACTIVATETIME4",
			"ERAB_DEACTIVATETIME5","ERAB_DEACTIVATETIME6","ERAB_DEACTIVATETIME7","ERAB_DEACTIVATETIME8","HO_FIRST_STARTTIME",
			"HO_FIRST_EVENTTIME","HO_FIRST_ENDTIME","HO_LAST_STARTTIME","HO_LAST_EVENTTIME","HO_LAST_ENDTIME","RLS_CALLRELEASETIME","RLS_REDIRECTION_TIME"};
	
	private static final String[] covertNumFields = {"CDR_TRACEID","HO_LAST_SRCTAC"};
	
	private static final String[] covertD2FFields = {"RLS_CELLRBPAYLOAD","RLS_CELLPDCCHPAYLOAD",
			"ERAB_UL_PACKET_DROPRATE1","ERAB_UL_PACKET_DROPRATE2","ERAB_UL_PACKET_DROPRATE3","ERAB_UL_PACKET_DROPRATE4","ERAB_UL_PACKET_DROPRATE5",
			"ERAB_UL_PACKET_DROPRATE6","ERAB_UL_PACKET_DROPRATE7","ERAB_UL_PACKET_DROPRATE8","ERAB_DL_PACKET_LOSSRATE1","ERAB_DL_PACKET_LOSSRATE2",
			"ERAB_DL_PACKET_LOSSRATE3","ERAB_DL_PACKET_LOSSRATE4","ERAB_DL_PACKET_LOSSRATE5","ERAB_DL_PACKET_LOSSRATE6","ERAB_DL_PACKET_LOSSRATE7",
			"ERAB_DL_PACKET_LOSSRATE8","AC_MR_LTESCPLRULQCI1","AC_MR_LTESCPLRULQCI2","AC_MR_LTESCPLRULQCI3","AC_MR_LTESCPLRULQCI4","AC_MR_LTESCPLRULQCI5",
			"AC_MR_LTESCPLRULQCI6","AC_MR_LTESCPLRULQCI7","AC_MR_LTESCPLRULQCI8","AC_MR_LTESCPLRULQCI9","AC_MR_LTESCPLRDLQCI1","AC_MR_LTESCPLRDLQCI2",
			"AC_MR_LTESCPLRDLQCI3","AC_MR_LTESCPLRDLQCI4","AC_MR_LTESCPLRDLQCI5","AC_MR_LTESCPLRDLQCI6","AC_MR_LTESCPLRDLQCI7","AC_MR_LTESCPLRDLQCI8","AC_MR_LTESCPLRDLQCI9"};
	
	private static List<String> sourceFields = null;
	
	private static List<String> encryptFields = null;
	
	static{
		if("y".equalsIgnoreCase(AppContext.getBean("cdrEncryptSwitch", String.class)) 
				&& AppContext.getBean("md5Members", String.class) != null){
			sourceFields = Arrays.asList(AppContext.getBean("md5Members", String.class).split(","));
			encryptFields = new ArrayList<String>(sourceFields.size());
			for(String fields:sourceFields){
				encryptFields.add(fields+"_MD5");
			}
		}
	}
	
	/**
	 * 是否是解析第一个文件(压缩包中的第一个文件)
	 */
	private boolean isFirstFileToParse = true;
	
	public ZteCdtCsvPublicParser(){
		String sMinOrientNeiCellsNumber = AppContext.getBean("minOrientNeiCellsNumber", String.class);
		if (sMinOrientNeiCellsNumber != null && org.apache.commons.lang.math.NumberUtils.isNumber(sMinOrientNeiCellsNumber)) {
			nMinOrientNeiCellsNumber = Integer.parseInt(sMinOrientNeiCellsNumber);
		}
	}
	
	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		if (accessOutObject == null)
			throw new IOException("接入对象无效，null.");
		
		this.streamOut = (StreamAccessOutObject) accessOutObject;
		adaptiveStream = new AdaptiveInputStream(streamOut.getOutObject(), streamOut.getRawAccessPackName());
		CompressionFileEntry entry = adaptiveStream.getNextEntry();
		if (entry == null)
			throw new IOException("接入对象无效，null.");
		
		if (isFirstFileToParse) {
			this.task = this.streamOut.getTask();
			this.rawFilePath = this.streamOut.getRawAccessName();
			this.startTime = new Timestamp(System.currentTimeMillis()); // 开始解析的时间。
			this.currentDataTime = parseFileName(rawFilePath);
			LOGGER.debug("开始解析: {}::{}", this.streamOut.getRawAccessPackName(), entry.fileName);
			// 解析模板 获取当前文件对应的templet
			this.parseTemplet();
			imsiQueryHelper = ImsiQueryHelper.getHelperInstance(this.task.getId());
			Date fileTime = parseFileName(this.rawFilePath);
			if (fileTime != null) {
				ImsiRequestResult result = imsiQueryHelper.isCacheReady(fileTime.getTime()+RAWFILE_UNIT_TIMESEC);
				if (result == null) {
					throw new TaskWorkTerminateException("查询IMSI服务器出错");
				}
				
				if (result.value != ImsiRequestResult.RESPONSE_CACHE_IS_READY) {
					String errMsg = ImsiRequestResult.getResponseValueDesc(result.value);
					LOGGER.debug("{} cdr时间:{}，核心网{}cache最大生成时间{}.", new Object[]{
							errMsg, 
							getDateString(fileTime),
							result.getRequestServerInfo(), 
							getDateString(new Date(result.maxServerTimeInCache) )});
			
					throw new TaskWorkTerminateException("核心网话单未达到关联时间条件或服务器出错, 将在下一个周期继续采集.");
				}
			} 
		} else {
			this.rawFilePath = this.streamOut.getRawAccessName();
			LOGGER.debug("开始解析子文件:{}", this.rawFilePath);
		}
		
		this.reader = new BufferedReader(new InputStreamReader(entry.inputStream, "GBK"), 16 * 1024);	
		// 解析文件头
		this.parseCdrHead(this.reader.readLine());
		
		imsiQueryHelper.asynParse(this, isFirstFileToParse);
		isFirstFileToParse = false;
	}
	
	@Override
	public void asynExtractAllRecords() throws Exception {
		if (this.cdrFieldNames == null || this.reader == null || this.currentDataTime == null) {
			return ;
		}
		
		while (!imsiQueryHelper.isCanceParseFlagSet() && getLineData()) {
			extractNextReord();
		}
	}
	
	private ParseOutRecord extractNextReord() throws TimeoutException, ParseException {
		// 拆封字段
		int nCsvNum = HwCdtCsvPublicParser.split(this.lineRecord, ',', lineValues);
		if (nCsvNum < 1)
			return null;
		Map<String, String> recordData = this.createExportPropertyMap(ParseOutRecord.DEFAULT_DATA_TYPE);
		for (int i = 0; i < cdrHeadInfo.length && i<nCsvNum; i++) {
			if(StringUtils.isBlank(lineValues[i]) || lineValues[i].equals("NC")){
				continue;
			}
			
			String fieldIndex = this.cdrHeadFieldIndex[i];
			if (fieldIndex == null)
				continue;
			
			recordData.put(fieldIndex, lineValues[i]);
		}
		
		try {
			conversionProcess(recordData);
		} catch (Exception e) {
			LOGGER.warn("字段处理异常。原因：", e);
		}
		
		// NE_CELL_L表关联获取网元信息。关联字段：VENDOR - E_NODE_ID - CONNECTION_CELL_ID。
		boolean isreturnNull = AssociateCellUtil.associateCellInfo(task,recordData,nMinOrientNeiCellsNumber,Vendor.VENDOR_ZTE);
		//接入与释放都关联不上city_id，在切换与最后切换时再关联一次
		if(isreturnNull && StringUtils.isEmpty(recordData.get("CITY_ID"))){
			AssociateCellUtil.associateCityId(recordData,Vendor.VENDOR_ZTE);
		}
		if(!isreturnNull){
			return null;
		}
		
		//IMSI MSISDN关联
		String cdrStartTime = recordData.get("CDR_STARTTIME");
		String mmeueS1apid = recordData.get("CDR_MMEUES1APID");
		String stmsi = recordData.get("CDR_STMSI");
		
		String mmegi = recordData.get("CDR_MMEGROUPID");
		String mmec = recordData.get("CDR_MMECODE");
		
		// 从S_TMSI中切出MTmsi，Mmec: 1 bytes， MTmsi: 4 bytes
		String mtmsi = null;
		if (stmsi != null && stmsi.length() > 0) {
			//数值类型 16进制  10进制
			if(stmsi.startsWith("0x")){
				mtmsi = Long.valueOf(stmsi.substring(2),16).toString();
			}else{
				mtmsi = Long.valueOf(stmsi,10).toString();
			}
		}
		
		// 2016-03-30 00:47:54
		Date cdrTime = null;
		if (cdrStartTime != null && cdrStartTime.length() >= 19)
		{
			cdrTime = cdrTimeFormat.parse(cdrStartTime); 
		}	
		
		// 判断下记录的有效性
		if (cdrTime == null || Math.abs((currentDataTime.getTime() - cdrTime.getTime())) > timeValidRange) {
			//badWriter.warn("非法文件时间记录.CDR_STARTTIME{}, record:{}", cdrStartTime, this.lineRecord);
			return null;
		}
		
		// 厂家标识
		recordData.put("VENDOR", "ZY0804");

		ParseOutRecord record = new ParseOutRecord();
		record.setType(ParseOutRecord.DEFAULT_DATA_TYPE);
		record.setRecord(recordData);
		
		if (	imsiQueryHelper != null 
				&& cdrTime != null
				&& (mtmsi != null || mmeueS1apid != null)
				&& mmegi != null && mmec != null) {
			
			imsiQueryHelper.submitMatchRecord(record, cdrTime.getTime()/* + zoneTimeOffset*/, 
					LteCoreCommonDataManager.converToLong(mmeueS1apid), 
					LteCoreCommonDataManager.converToLong(mtmsi), 
					LteCoreCommonDataManager.converToInteger(mmegi),
					LteCoreCommonDataManager.converToInteger(mmec));
		}
		
		return record;
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		if (imsiQueryHelper == null)
			return false;
		
		return imsiQueryHelper.hasMatchedRecord();
	}

	/**
	 * 读取一行记录
	 * 
	 * @return
	 */
	private final boolean getLineData() {
		if (this.reader == null) {
			return false;
		}
		try {
			while (true) {
				if ((this.lineRecord = this.reader.readLine()) != null) {
					lineRecord = lineRecord.trim();
					if (lineRecord.length() < 1) {
						continue;
					}
					return true;
				}
				
				CompressionFileEntry entry = adaptiveStream.getNextEntry();
				if (entry != null) {
					this.reader = new BufferedReader(new InputStreamReader(entry.inputStream, "GBK"), 16 * 1024);
					// 忽略掉文件头
					this.reader.readLine();
					LOGGER.debug("开始解析: {}::{}", this.streamOut.getRawAccessPackName(), entry.fileName);
					continue;
				}
				
				break;
			}
		} catch (Exception e) {
			LOGGER.error("文件解析异常。文件：{}，异常信息：{}", this.rawFilePath, e.getMessage());
			return false;
		}
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		ParseOutRecord record = imsiQueryHelper.getMatchedRecord();
		this.encryptFields(record.getRecord(),sourceFields, encryptFields);
		readLineNum++;
		return record;
	}
	
	/**
	 * 频点数据 需要转换
	 * @param dl_ear_fcn
	 * @return
	 */
	public float changeDlEarFun(Double dl_ear_fcn){
		if(dl_ear_fcn == null){
			return 2.1f;
		}else if(dl_ear_fcn <= 599){
			return 2.1f;
		}else if(dl_ear_fcn <= 1949){
			return 1.8f;
		}else if(dl_ear_fcn <= 9039){
			return 0.8f;
		}else{
			return 2.6f;
		}
	}

	protected final LONG_LAT doLocation(DEV_TYPE devType, ONEWAYDELAY_CELL[] cellInfo) {
		LONG_LAT outLL = new LONG_LAT();
		Arrays.sort(cellInfo, CellStrengthComparator.comparatorInstance);
		try {
			OrientationAPI.doLocation_LTE(cellInfo, new OneWayDelayDist(devType, DiffuseInfo.DEFAULT_VAL), outLL);
		} catch (Exception e) {
			LOGGER.error("定位失败，taskId：{}，bsc：{}，cells：{}", new Object[]{this.task.getId(), this.task.getExtraInfo().getBscId(),
					(cellInfo != null ? Arrays.asList(cellInfo) : "<null>")});
		}
		return outLL;
	}

	/**
	 * 数据处理
	 * 
	 * @param recordData
	 */
	private void conversionProcess(Map<String, String> recordData) throws Exception {
		conversionNE(recordData,"HO_FIRST_SRCENBID","HO_FIRST_SRCCELLID");
		conversionNE(recordData,"HO_FIRST_TGTENBID","HO_FIRST_TGTCELLID");
		conversionNE(recordData,"HO_LAST_SRCENBID","HO_LAST_SRCCELLID");
		conversionNE(recordData,"HO_LAST_TGTENBID","HO_LAST_TGTCELLID");
		conversionNE(recordData,"RLS_ENBID","RLS_CELLID");
		
		for(String field:timeFields) {
			processCdrTime(recordData,field);
		}
		
		for(String field:covertNumFields) {
			if(StringUtil.isNotEmpty(recordData.get(field))) {
				recordData.put(field, Long.parseLong(recordData.get(field),16)+"");
			}
		}
		
		//呼叫与切换部分字段换算
		AssociateCellUtil.conversion_MR_LteScPHR(recordData);
		AssociateCellUtil.conversionFieldPlus140(recordData);
		AssociateCellUtil.conversionFieldPlus20Addhalf(recordData);
		
		convertDouble6Field(recordData, "RLS_LONGITUDE");
		convertDouble6Field(recordData, "RLS_LATITUDE");

		for(String field:covertD2FFields) {
			convertDouble2Field(recordData, field);
		}
	}

	/**
	 * 格式化字段值(6位精度)
	 * @param recordData
	 * @param fieldName
	 */
	private void convertDouble6Field(Map<String, String> recordData, String fieldName) {
		String value = recordData.get(fieldName);
		if (value != null && value.length() > 0) {
			recordData.put(fieldName, d6f.format(Double.parseDouble(value.trim())));
		}
	}

	/**
	 * 格式化字段值(2位精度)
	 * @param recordData
	 * @param fieldName
	 */
	private void convertDouble2Field(Map<String, String> recordData, String fieldName) {
		String value = recordData.get(fieldName);
		if (value != null && value.length() > 0) {
			recordData.put(fieldName, d2f.format(Double.parseDouble(value.trim())));
		}
	}
	
	private void conversionNE(Map<String, String> recordData,String enbId,String cellId)
	{
		// 转换成16进制，前5位为enodebid，后2位为cellid；
		String cellValue = recordData.get(cellId);
		if(StringUtil.isNotEmpty(cellValue))
		{
			Long nObjectID = Long.parseLong(cellValue);
			Long nEnbid = ((nObjectID >> 8) & 0xFFFFF);
			Long nCellid = (nObjectID & 0xFF);
			recordData.put(enbId, nEnbid == null?"":nEnbid.toString());
			recordData.put(cellId, nCellid == null?"":nCellid.toString());
		}
	}
	
	/**
	 * 解析话单头信息
	 * 
	 * @throws Exception
	 */
	public final boolean parseCdrHead(String headStr) {
		if (headStr == null || "".equals(headStr.trim())) {
			LOGGER.warn("文件消息头解析异常，文件：{}、消息头信息：", new Object[]{this.rawFilePath, headStr});
			return false;
		}
		this.cdrHeadInfo = headStr.toUpperCase().split("\\,");
		this.cdrHeadFieldIndex = null;
		if (this.cdrHeadInfo != null) {
			this.cdrHeadFieldIndex = new String[this.cdrHeadInfo.length];
			int i = 0;
			for (String headName : this.cdrHeadInfo) {
				this.cdrHeadFieldIndex[i++] = this.cdrFieldNames.get(headName);
			}
		}
		return true;
	}

	/**
	 * 从文件名获取时间
	 * 	package: FDD-LTE_CDR_ALCATEL_OMCR_266512_20160302000000.csv.gz
	 * 	item:	FDD-LTE_CDR_ALCATEL_OMCR_266512_20160302000000.csv
	 * @param entryFileName
	 * @return
	 */
	public Date parseFileName(String entryFileName) {
		String patternTime = entryFileName.substring(entryFileName.lastIndexOf("_")+1, entryFileName.lastIndexOf("."));
		Date date = null;
		if (patternTime != null) {
			try {
				date = cdrFileTimeFormat.parse(patternTime);
				return date;
			} catch (ParseException e) {}
		}
		return null;
	}
	
	/**
	 * 转换cdr的时间点，加上时区
	 * @param recordData
	 * @param fieldName
	 */
	public void processCdrTime(Map<String, String> recordData, String fieldName) {
		String value = recordData.get(fieldName);
		if (value == null || "".equals(value.trim()))
			return;
		try {
			// 时间格式为2016-02-25T08:46:56.360 或2016-02-25T08:46:56
            if(value.indexOf("T") > -1)
            {
            	value = value.replace("T", " ");
            }else if(value.indexOf("_") > -1){
            	value = value.replace("_", " ");
            }
//			Date d = new Date(Long.parseLong(value));
//			d.setTime(d.getTime());
//			value = cdrTimeFormat.format(d);
            if(value.indexOf(".") > -1)
            {
            	value = value.substring(0, value.indexOf("."));
            }
			recordData.put(fieldName, value);
		} catch (Exception e) {}
	}

	@Override
	public void close() {
		if (this.imsiQueryHelper != null) {
			this.imsiQueryHelper.endParse();
		}
		
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("[{}]-中兴cdr解析，处理{}条记录", new Object[]{task.getId(), readLineNum});
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public final void parseTemplet() throws Exception {
		// 解析模板
		TempletParser csvTempletParser = new CSVCfcTempletParser();
		csvTempletParser.tempfilepath = templates;
		csvTempletParser.parseTemp();
		Templet templet = csvTempletParser.getTemplets().get("-100");
		this.cdrFieldNames = new HashMap<>();
		for (Field field : templet.fieldList) {
			if (field == null) {
				continue;
			}
			this.cdrFieldNames.put(field.getName(), field.getIndex());
		}
	}

	
	@Override
	public List<ParseOutRecord> getAllRecords() {
		return null;
	}

	@Override
	public Date getDataTime(ParseOutRecord outRecord) {
		return this.currentDataTime;
	}
	
	public String getDateString(Date date) {
		if (date == null)
			return null;
		
		return TimeUtil.getDateString(date);
	}
	
	public static void main(String[] args) throws ParseException, InterruptedException {
		DecimalFormat d2f = new DecimalFormat(".00");
		System.out.println(d2f.format(Double.parseDouble("")));
	}
}

