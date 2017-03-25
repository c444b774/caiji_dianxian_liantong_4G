package cn.uway.igp.lte.parser.cdt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.AbstractMap.SimpleEntry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.accessor.StreamAccessOutObject;
import cn.uway.framework.context.AppContext;
import cn.uway.framework.context.Vendor;
import cn.uway.framework.orientation.GridOrientation;
import cn.uway.framework.orientation.OrientationAPI;
import cn.uway.framework.orientation.Type.LONG_LAT;
import cn.uway.framework.parser.AbstractParser;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.templet.CSVCfcTempletParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.igp.lte.context.common.CommonSystemConfigMgr;
import cn.uway.igp.lte.extraDataCache.cache.CityInfo;
import cn.uway.igp.lte.extraDataCache.cache.CityInfoCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgInfo;
import cn.uway.igp.lte.parser.cdt.CoreCommonDataManager.CommonDataEntry;
import cn.uway.util.IoUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class HwCdrGzCsvParser extends AbstractParser {

	private static Logger LOGGER = LoggerFactory.getLogger(HwCdrGzCsvParser.class);

	//private static final Logger badWriter = BadWriter.getInstance().getBadWriter();

	public StreamAccessOutObject streamOut;

	public String rawFilePath = null; // 要解析的文件

	/**
	 * 压缩文件名
	 */
	public String zipFileName;

//	/** 输入zip流 */
//	public ZipInputStream zipstream;
//
//	/**
//	 * 解压缩文件对象
//	 */
//	public ZipEntry entry = null;

	public String rawFileName;

	public BufferedReader reader = null;

	public String[] cdrHeadInfo = null;

	public HashMap<String, String> cdrFieldNames = null;

	public String lineRecord = null;

	public long readLineNum = 0; // 记录总行数

	public String lastTimeOfCallConnection;

	// 保留小数点后5位
	public DecimalFormat df = new DecimalFormat(".00000");
	
	protected CoreCommonDataManager coreCommonDataManager;
	
	protected Long curr_enb_id;
	
	/**
	 * 时区偏移值(默认偏移8小时)
	 */
	private long zoneTimeOffset = 8 * 60 * 60 * 1000L;
	
	private List<SimpleEntry<Long, String>> parseFileList = null;
	
	private InputStream fileCurrLocalStream = null;
	private File fileCurrLocalRawData = null;
	
	private static final String TMP_DIR = "./tmp/";
	
	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		if (accessOutObject == null)
			throw new IOException("接入对象无效，null.");
		this.streamOut = (StreamAccessOutObject) accessOutObject;
		this.task = this.streamOut.getTask();
		this.rawFilePath = this.streamOut.getRawAccessName();
		this.zipFileName = FilenameUtils.getBaseName(rawFilePath);
		this.startTime = new Timestamp(System.currentTimeMillis()); // 开始解析的时间。
		LOGGER.debug("开始解码:{}", this.rawFilePath);
		// 解析模板 获取当前文件对应的templet
		this.parseTemplet();
		
		if (this.coreCommonDataManager == null) {
			coreCommonDataManager = AppContext.getBean("lteCoreCommonDataReadConfig", CoreCommonDataManager.class);
			coreCommonDataManager.init();
		}
		
		if (coreCommonDataManager.isEnableState()) {
			Date fileTime = getHwLteWirelessFileTime(this.rawFilePath);
			if (fileTime != null) {
				if (!coreCommonDataManager.isCacheReady(fileTime)) {
					LOGGER.debug("核心网话单未达到关联时间限制, 将在下一个周期继续采集 cdr时间:{}，核心网cache最大生成时间{}."
							, getDateString(fileTime)
							, getDateString(coreCommonDataManager.getMaxCacheDateTime()));
					
					throw new Exception("核心网话单未达到关联时间限制, 将在下一个周期继续采集.");
				}
			}
		}
		
		parseFileList = null;
		// 转换为缓冲流读取
		if (this.rawFilePath.endsWith(".zip")) {
//			this.zipstream = new ZipInputStream(this.streamOut.getOutObject());
//			this.parserZipStream();
			ZipInputStream zipInput = new ZipInputStream(this.streamOut.getOutObject());
			parseFileList = extractZipStream(zipInput);
			if (parseFileList.size() < 1) {
				LOGGER.warn("压缩包中无文件，文件解析异常。文件：{}", this.streamOut.getRawAccessName());
				return;
			}
			parseNextFile();
		}
		// else if (accessOutObject.getRawAccessName().endsWith(".gz")) {
		// this.reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(this.streamOut.getOutObject()), "GBK"), 16 * 1024);
		// // 解析文件头
		// this.parseCdrHead(this.reader.readLine());
		// }
		else {
			LOGGER.warn("文件解析异常。文件：{}", this.rawFilePath);
			return;
		}
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		if (this.cdrFieldNames == null || this.reader == null || this.currentDataTime == null) {
			return false;
		}
		boolean isLineData = getLineData();
		while (!isLineData) {
			if (this.parseNextFile()) {
				isLineData = getLineData();
			} else {
				return false;
			}
		}
		return isLineData;
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
			while ((this.lineRecord = this.reader.readLine()) != null) {
				if ("".equals(lineRecord.trim())) {
					continue;
				}
				return true;
			}
		} catch (Exception e) {
			LOGGER.error("文件解析异常。文件：{}，异常信息：{}", this.rawFilePath, e.getMessage());
			return false;
		}
		return false;
	}

	/**
	 * 解析压缩包
	 * 
	 * @return
	 * @throws Exception
	 */
	public final boolean parseFileEntry(String fileName, InputStream stream) {
		try {
			//TODO:因为华为cdr里面的文件时间跳跃非常大，每解一个子包，需要释放一下，必要的内存，否则内存会撑爆
			coreCommonDataManager.releaseEnbCache(0);
			
			this.rawFileName = fileName;
			// 解析文件名
			this.parseFileName();
			
			// 转换为缓冲流读取
			this.reader = new BufferedReader(new InputStreamReader(stream, "GBK"), 16 * 1024);
			// 解析文件头
			if (!this.parseCdrHead(this.reader.readLine())) {
				return false;
			}
			
			return true;
		} catch (Exception e) {
			LOGGER.error("华为CDT-csv文件读取压缩流异常。文件名：{}、压缩文件：{}、异常信息：{}", new Object[]{this.rawFileName, this.zipFileName, e.getMessage()});
		}
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		// 拆封字段
		String[] cdrData = StringUtil.split(this.lineRecord, ",");
		Map<String, String> recordData = this.createExportPropertyMap(ParseOutRecord.DEFAULT_DATA_TYPE);
		for (int i = 0; i < cdrHeadInfo.length; i++) {
			String fieldIndex = this.cdrFieldNames.get(cdrHeadInfo[i]);
			if (fieldIndex == null)
				continue;
			// if ("".equals(cdrData[i].trim())) {
			// recordData.put(fieldIndex, null);
			// continue;
			// }
			if (fieldIndex.equals("TIME_OF_CALL_DROP")) {
				if ("N/A".equalsIgnoreCase(cdrData[i]) || "".equals(cdrData[i].trim())) {
					continue;
				}
			} else if (fieldIndex.equals("TIME_OF_CALL_CONNECTION")) {
				if ("N/A".equalsIgnoreCase(cdrData[i]) || "".equals(cdrData[i].trim())) {
					continue;
				}
				this.lastTimeOfCallConnection = cdrData[i];
			}
			recordData.put(fieldIndex, cdrData[i]);
		}
		try {
			conversionProcess(recordData);
		} catch (Exception e) {
			LOGGER.error("字段处理异常。原因：", e);
		}
		// NE_CELL_L表关联获取网元信息。关联字段：VENDOR - E_NODE_ID - CONNECTION_CELL_ID。
		LteCellCfgInfo lteCellCfgInfo = LteCellCfgCache.findNeCellByVendorEnbCell(Vendor.VENDOR_HW, recordData.get("E_NODE_ID"),
				recordData.get("CONNECTION_CELL_ID"));
		if (lteCellCfgInfo != null) {
			/**
			 * 网元回填如下字段 NE_ENB_ID NE_CELL_ID COUNTY_ID COUNTY_NAME CITY_ID CITY_NAME FDDTDDIND
			 */
			recordData.put("NE_ENB_ID", String.valueOf(lteCellCfgInfo.neEnbId));
			recordData.put("NE_CELL_ID", String.valueOf(lteCellCfgInfo.neCellId));
			recordData.put("COUNTY_ID", String.valueOf(lteCellCfgInfo.countyId));
			recordData.put("COUNTY_NAME", lteCellCfgInfo.countyName);
			recordData.put("CITY_ID", String.valueOf(lteCellCfgInfo.cityId));
			recordData.put("CITY_NAME", lteCellCfgInfo.cityName);
			recordData.put("FDDTDDIND", lteCellCfgInfo.fddTddInd);
		} else if (CommonSystemConfigMgr.isNeNotExistIgnore()) {
//			badWriter.warn("[{}]关联不到网元：VENDOR - ENB_ID - CELL_ID = ZY0808-{}-{}。", new Object[]{this.rawFileName, recordData.get("E_NODE_ID"),
//					recordData.get("CONNECTION_CELL_ID")});
		}
		String timeOfCallConnection = recordData.get("TIME_OF_CALL_CONNECTION");
		String timeOfCallDrop = recordData.get("TIME_OF_CALL_DROP");
		if (timeOfCallConnection == null) {
			if (timeOfCallDrop != null) {
				recordData.put("TIME_OF_CALL_CONNECTION", timeOfCallDrop);
			} else {
				recordData.put("TIME_OF_CALL_CONNECTION", this.lastTimeOfCallConnection);
			}
		}
		// 接入定位
		String connection_ta = recordData.get("CONNECTION_TA");
		if (lteCellCfgInfo != null && lteCellCfgInfo.direct_angle != null && StringUtil.isNotEmpty(connection_ta)) {
			LONG_LAT accessLocation = new LONG_LAT();
			OrientationAPI.LteTaOrientation(Long.parseLong(connection_ta), lteCellCfgInfo.direct_angle, lteCellCfgInfo.longitude,
					lteCellCfgInfo.latitude, accessLocation);

			recordData.put("LONGITUDE", df.format(accessLocation.LON));
			recordData.put("LATITUDE", df.format(accessLocation.LAT));

			// 填充栅格信息
			String[] gridInfo = computGridInfo(accessLocation, lteCellCfgInfo.cityId);
			recordData.put("GRID_M", gridInfo[0]);
			recordData.put("GRID_N", gridInfo[1]);
			recordData.put("GRID_ID", gridInfo[2]);
		}

		// 释放定位--因没有释放连接时间提前量，暂不做
		
		//IMSI MSISDN关联
		{
			String timeOfCallConn = recordData.get("TIME_OF_CALL_CONNECTION");
			String mmeueS1apid = recordData.get("MMEUES1APID");
			String stmsi = recordData.get("S_TMSI");
			String gummeid = recordData.get("GUMMEID");
			
			// 从GUMMEID中切出mmegi和mmec
			String mmegi = null;
			String mmec = null;
			if (gummeid != null) {
				String[] vals = gummeid.split("-");
				if (vals != null && vals.length == 3) {
					mmegi = vals[1];
					mmec = vals[2];
				}
			}
			
			// 从S_TMSI中切出TMSI
			String mtmsi = null;
			if (stmsi != null) {
				int pos = stmsi.indexOf("-");
				if (pos >0) 
					mtmsi = stmsi.substring(pos+1);
			}
			
			// 20150330004754
			Date dateTimeOfCallConn = null;
			if (timeOfCallConn != null && timeOfCallConn.length() >= 14)
				dateTimeOfCallConn = TimeUtil.getyyyyMMddHHmmssDate(timeOfCallConn); 
			
			if (dateTimeOfCallConn != null
					&& (mtmsi != null || mmeueS1apid != null)
					&& mmegi != null && mmec != null) {
				
				/*if (curr_enb_id == null) {
					curr_enb_id = nEnbid;
				} else if (!curr_enb_id.equals(nEnbid)) {
					coreCommonDataManager.releaseEnbCache(curr_enb_id);
					curr_enb_id = nEnbid;
				}*/
								
				CommonDataEntry entry = coreCommonDataManager.getHWCdrCache(dateTimeOfCallConn.getTime() + zoneTimeOffset,
						 CoreCommonDataManager.converToLong(mmeueS1apid), 
						 CoreCommonDataManager.converToLong(mtmsi), 
						 CoreCommonDataManager.converToLong(mmegi),
						 CoreCommonDataManager.converToLong(mmec)
						);
				if (entry != null) {
					recordData.put("IMSI", entry.getImsi());
					recordData.put("MSISDN", entry.getMsisdn());
				}
			}
		}

		ParseOutRecord record = new ParseOutRecord();
		record.setType(ParseOutRecord.DEFAULT_DATA_TYPE);
		record.setRecord(recordData);
		readLineNum++;
		//return null;
		return record;
	}

	/**
	 * 数据处理
	 * 
	 * @param recordData
	 */
	private void conversionProcess(Map<String, String> recordData) throws Exception {
		int index = -1;
		String upLinkRscp = recordData.get("UPLINK_RSRP");
		if (upLinkRscp != null && (index = upLinkRscp.indexOf(" ")) != -1) {
			Float val = Float.valueOf(upLinkRscp.substring(0, index)) * 0.01F;
			recordData.put("UPLINK_RSRP", String.valueOf(val));
		}
		String upLinkSinr = recordData.get("UPLINK_SINR");
		if (upLinkSinr != null && (index = upLinkSinr.indexOf(" ")) != -1) {
			Float val = Float.valueOf(upLinkSinr.substring(0, index)) * 0.01F;
			recordData.put("UPLINK_SINR", String.valueOf(val));
		}
		String callSteupTimePerSection = recordData.get("CALL_SETUP_TIME_PER_SECTION");
		if (callSteupTimePerSection != null) {
			index = callSteupTimePerSection.indexOf(" ");
			if (index != -1)
				recordData.put("CALL_SETUP_TIME_PER_SECTION", callSteupTimePerSection.substring(0, index));
		}
		String uePowerHeadroom = recordData.get("UE_POWER_HEADROOM");
		if (uePowerHeadroom != null) {
			index = uePowerHeadroom.indexOf(" ");
			if (index != -1)
				recordData.put("UE_POWER_HEADROOM", uePowerHeadroom.substring(0, index));
		}
		String sourceCellRsrp = recordData.get("SOURCE_CELL_RSRP");
		if (sourceCellRsrp != null) {
			index = sourceCellRsrp.indexOf(" ");
			if (index != -1)
				recordData.put("SOURCE_CELL_RSRP", sourceCellRsrp.substring(0, index));
		}
		String sourceCellRsrq = recordData.get("SOURCE_CELL_RSRQ");
		if (sourceCellRsrq != null) {
			index = sourceCellRsrq.indexOf(" ");
			if (index != -1)
				recordData.put("SOURCE_CELL_RSRQ", sourceCellRsrq.substring(0, index));
		}
		String neighborCellRsrp = recordData.get("NEIGHBOR_CELL_RSRP");
		if (neighborCellRsrp != null) {
			index = neighborCellRsrp.indexOf(" ");
			if (index != -1)
				recordData.put("NEIGHBOR_CELL_RSRP", neighborCellRsrp.substring(0, index));
		}
		String neighborCellRsrq = recordData.get("NEIGHBOR_CELL_RSRQ");
		if (neighborCellRsrq != null) {
			index = neighborCellRsrq.indexOf(" ");
			if (index != -1)
				recordData.put("NEIGHBOR_CELL_RSRQ", neighborCellRsrq.substring(0, index));
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
		this.cdrHeadInfo = StringUtil.split(headStr.toUpperCase(), ",");
		return true;
	}

	/**
	 * 解析文件名
	 * 
	 * @throws Exception
	 */
	public void parseFileName() {
		LOGGER.debug("开始从:{}中解析子文件:{}", this.rawFilePath, this.rawFileName);
		// 文件名：258168_20140917142008.csv
		try {
			String patternTime = StringUtil.getPattern(this.rawFileName, "\\d{10}");
			this.lastTimeOfCallConnection = patternTime;
			this.currentDataTime = TimeUtil.getDate(patternTime, "yyyyMMddHH");
		} catch (Exception e) {
			LOGGER.error("文件名{}，解析文件名异常。{}", this.rawFileName, e.getMessage());
		}
	}
	
	/**
	 * 从文件名获取时间
	 * 	package: HW_20150726.0300.zip
	 * 	item:	276529_20150726031133.csv
	 * @param entryFileName
	 * @return
	 */
	public static Date getHwLteWirelessFileTime(String entryFileName) {
		String patternTime = StringUtil.getPattern(entryFileName, "\\d{14}");
		Date date = null;
		if (patternTime != null) {
			try {
				date = TimeUtil.getDate(patternTime, "yyyyMMddHHmmss");
				return date;
			} catch (ParseException e) {}
		}
		
		patternTime = StringUtil.getPattern(entryFileName, "\\d{8}[.]\\d{4}");
		if (patternTime != null) {
			try {
				date = TimeUtil.getDate(patternTime, "yyyyMMdd.HHmm");
				return date;
			} catch (ParseException e) {}
		}
		
		return null;
	}

	@Override
	public void close() {
		//if (curr_enb_id != null) {
			coreCommonDataManager.releaseEnbCache(0);
		//}
		
		if (fileCurrLocalStream != null) {
			try {
				fileCurrLocalStream.close();
			} catch (IOException e) {}
			fileCurrLocalStream = null;
		}
			
		if (fileCurrLocalRawData != null && fileCurrLocalRawData.exists()) {
			fileCurrLocalRawData.delete();
			fileCurrLocalRawData = null;
		}
		
		// 删除掉未解完的文件
		while (parseFileList != null && parseFileList.size() > 0) {
			File file = new File(parseFileList.remove(0).getValue());
			if (file.exists() && file.isFile())
				file.delete();
		}
		
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("[{}]-华为cdr解析，处理{}条记录", new Object[]{task.getId(), readLineNum});
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

	/**
	 * 通过经度、维度信息计算栅格
	 * 
	 * @param outLL
	 * @return 栅格信息
	 */
	protected String[] computGridInfo(LONG_LAT outLL, int cityId) {
		if (outLL.LAT == 0.0 && outLL.LON == 0.0)
			return new String[]{"0", "0", ""};
		String[] gridInfos = new String[3];
		gridInfos[0] = "0";
		gridInfos[1] = "0";
		gridInfos[2] = "";
		CityInfo cityInfo = CityInfoCache.findCity(cityId);
		// 如果城市信息未找到 则返回默认GRID信息 M/N均为0
		if (cityInfo == null)
			return gridInfos;
		double longitude = outLL.LON;
		double latitude = outLL.LAT;
		// 确定定位出来后的经纬度在当前城市范围内
		if (outLL.LON > cityInfo.longRt) {
			longitude = cityInfo.longRt;
		} else if (outLL.LON < cityInfo.longLt) {
			longitude = cityInfo.longLt;
		}
		if (outLL.LAT > cityInfo.latLt) {
			latitude = cityInfo.latLt;
		} else if (outLL.LAT < cityInfo.latRt) {
			latitude = cityInfo.latRt;
		}
		return GridOrientation.orientationGridInfo(longitude, latitude, cityInfo.longLt, cityInfo.longRt, cityInfo.latLt, cityInfo.latRt,
				cityInfo.getGridM(), cityInfo.getGridN());
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
	
	public List<SimpleEntry<Long, String>> extractZipStream(ZipInputStream zipInput) throws IOException {
		List<SimpleEntry<Long, String>> targetFileList = new LinkedList<SimpleEntry<Long, String>>();
		String dirname = TMP_DIR + this.task.getId() + "/";
		File dir = new File(dirname);
		if (!dir.exists()) {
			if (!dir.mkdirs())
				throw new IOException("创建临时目录失败：" + dir.getAbsolutePath());
		}
		
		// 清空目录下的文件
		File[] files = dir.listFiles();
		for (File file : files) {
			if (file.isFile()) {
				file.delete();
			}
		}
		
		ZipEntry entry = null;
		while ((entry=zipInput.getNextEntry()) != null) {
			String targetFileName = dirname + entry.getName();
			OutputStream output = null;
			try {
				output = new FileOutputStream(targetFileName);
				IOUtils.copy(zipInput, output);
				output.flush();
				IoUtil.closeQuietly(output);
				
				//boolean bIncludeSpecificHeadFileTag = (targetFileName.indexOf(headFileTag) > 0);
				//276529_20150726031133.csv
				long fileKey = getHwLteWirelessFileTime(targetFileName).getTime();
				//if (!bIncludeSpecificHeadFileTag) {
				//	fileKey = (1L<<62) | (fileKey/1000);
				//}
				
				targetFileList.add(new SimpleEntry<Long, String>(fileKey, targetFileName));
			} catch (Exception e) {
				throw e;
			} finally {
				IoUtil.closeQuietly(output);
			}
		}
		
		Collections.sort(targetFileList, new Comparator<SimpleEntry<Long, String>>() {
			@Override
			public int compare(SimpleEntry<Long, String> arg0, SimpleEntry<Long, String> arg1) {
				return arg0.getKey().compareTo(arg1.getKey());
			}
			
		});
		
		return targetFileList;
	}
	
	public boolean parseNextFile() throws IOException {
		while (true) {
			if (parseFileList == null || parseFileList.size() < 1)
				return false;
			
			if (fileCurrLocalStream != null) {
				try {
					fileCurrLocalStream.close();
				} catch (IOException e) {}
				fileCurrLocalStream = null;
			}
			
			if (fileCurrLocalRawData != null && fileCurrLocalRawData.exists()) {
				fileCurrLocalRawData.delete();
				fileCurrLocalRawData = null;
			}
			
			String fileName = parseFileList.remove(0).getValue();
			fileCurrLocalRawData = new File(fileName);
			if (!fileCurrLocalRawData.exists())
				continue;
			
			fileCurrLocalStream = new FileInputStream(fileName);
			if (parseFileEntry(fileName, fileCurrLocalStream))
				return true;
		}
	}
}
