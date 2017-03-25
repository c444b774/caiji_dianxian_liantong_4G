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
import java.text.SimpleDateFormat;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.accessor.StreamAccessOutObject;
import cn.uway.framework.context.Vendor;
import cn.uway.framework.job.AdaptiveInputStream;
import cn.uway.framework.job.AdaptiveInputStream.CompressionFileEntry;
import cn.uway.framework.log.BadWriter;
import cn.uway.framework.orientation.GridOrientation;
import cn.uway.framework.orientation.Type.DEV_TYPE;
import cn.uway.framework.orientation.Type.LONG_LAT;
import cn.uway.framework.orientation.Type.ONEWAYDELAY_CELL;
import cn.uway.framework.parser.AbstractParser;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.templet.CSVCfcTempletParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.framework.task.worker.TaskWorkTerminateException;
import cn.uway.igp.lte.context.common.CommonSystemConfigMgr;
import cn.uway.igp.lte.extraDataCache.cache.CityInfo;
import cn.uway.igp.lte.extraDataCache.cache.CityInfoCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgInfo;
import cn.uway.igp.lte.extraDataCache.cache.LteNeiCellCfgDynamicCache;
import cn.uway.igp.lte.extraDataCache.cache.LteNeiCellCfgInfo;
import cn.uway.igp.lte.service.AbsImsiQuerySession.ImsiRequestResult;
import cn.uway.igp.lte.service.ImsiQueryHelper;
import cn.uway.igp.lte.service.LteCoreCommonDataManager;
import cn.uway.igp.lte.util.LTEOrientUtil;
import cn.uway.util.IoUtil;

public class ZteCdtCsvParser extends AbstractParser {
	private static Logger LOGGER = LoggerFactory.getLogger(ZteCdtCsvParser.class);

	private static final Logger badWriter = BadWriter.getInstance().getBadWriter();

	private static final long START_DATA = 946656000000l; // 2000-01-01 00:00:00 基准时间

	private SimpleDateFormat dataFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	//中兴FDD-LTE_CDT_ZTE_V3.2_373058_20160622160000 版本
	private SimpleDateFormat dataFormat2 = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
	
	private Pattern fileNameEnbIDPattern = Pattern.compile("_\\d{6}_");
	
	private Pattern[] fileTimePatterns = {
			Pattern.compile("\\d{4}[-]\\d{2}[-]\\d{2}[_]\\d{2}[-]\\d{2}[-]\\d{2}"),
			Pattern.compile("20\\d{12}"),
			Pattern.compile("\\d{8}[.]\\d{4}"),
	};
	
	private SimpleDateFormat[] fileDateFormat = {
			new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss"),
			new SimpleDateFormat("yyyyMMddHHmmss"),
			new SimpleDateFormat("yyyyMMdd.HHmm")
	};
	
	
	// 保留小数点后5位
	protected DecimalFormat df = new DecimalFormat(".00000");

	public Map<String, Templet> templetMap = null;

	public Templet templet = null;

	public StreamAccessOutObject streamOut;

	public BufferedReader reader = null;

	public String rawFilePath = null; // 要解析的文件名

	public String rawFileName = null;

	public String[] cdtHeadInfo = null;

	public HashMap<String, String> cdtFieldNames = null;

	public String lineRecord = null;

	public String dataType = null;

	public long readLineNum = 0; // 记录总行数

	public String tempSplitSign = "`~";
	
	protected ImsiQueryHelper imsiQueryHelper;
	
	private Integer curr_enb_id;
	
	private static final String TMP_DIR = "./tmp/";
	
	private static final String headFileTag1 = "_32.";
	private static final String headFileTag2 = "_Setup.";
	
	private List<SimpleEntry<Long, String>> parseFileList = null;
	
	private Map<String, String> mapDataCache = new HashMap<String, String>(3000);
	
	private File fileCurrLocalRawData = null;
	private InputStream fileCurrLocalStream = null;
	
	private StringBuilder sbKeyValueBuilder = new StringBuilder(200);
	
	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		if (accessOutObject == null)
			throw new IOException("接入对象无效，null.");
		this.streamOut = (StreamAccessOutObject) accessOutObject;
		this.task = this.streamOut.getTask();
		this.curr_enb_id = null;
		this.mapDataCache.clear();
		
		// zte_nj2_20150726.0020.zip
		//LTETDD_140915_CDT_264998_2015-07-26_00-20-00_33.csv
		this.startTime = new Timestamp(System.currentTimeMillis()); // 开始解析的时间。
		this.rawFilePath = this.streamOut.getRawAccessName();
		
		if (ImsiQueryHelper.isImsiServerEnable()) {
			imsiQueryHelper = ImsiQueryHelper.getHelperInstance(this.task.getId());
			Date fileTime = getZteLteWirelessFileTime(this.rawFilePath);
			if (fileTime != null) {
				ImsiRequestResult result = imsiQueryHelper.isCacheReady(fileTime.getTime());
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
		}

		// 解析模板 获取当前文件对应的templet
		this.parseTemplet();
		
		parseFileList = null;
		// 转换为缓冲流读取
		if (this.rawFilePath.endsWith(".zip")) {
			//ZipInputStream zipInput = new ZipInputStream(this.streamOut.getOutObject());
			parseFileList = extractZipStream(this.streamOut.getOutObject(), this.rawFilePath);
			if (parseFileList.size() < 1) {
				LOGGER.warn("压缩包中无文件，文件解析异常。文件：{}", this.streamOut.getRawAccessName());
				return;
			}
			parseNextFile();
		} else if (accessOutObject.getRawAccessName().endsWith(".gz")) {
			parseFileEntry(this.rawFilePath, new GZIPInputStream(this.streamOut.getOutObject()));
		} else {
			parseFileEntry(this.rawFilePath, this.streamOut.getOutObject());
		}
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
	
	public boolean parseFileEntry(String fileName, InputStream stream) throws IOException {
		this.templet = null;
		this.cdtHeadInfo = null;
		this.rawFileName = FilenameUtils.getBaseName(fileName);
		int enbID = parseEnbID(fileName);
		if (this.curr_enb_id == null || this.curr_enb_id != enbID) {
			this.curr_enb_id = enbID;
			if (this.mapDataCache != null) {
				this.mapDataCache.clear();
			}
		}
		
		LOGGER.debug("开始解析子文件:{}, 从：{}", this.rawFileName, this.rawFilePath);
		this.parseFileName(this.rawFileName);
		if (null == this.dataType) {
			return false;
		}
		
		// 获得数据类型。
		if (!this.findMyTemplet(this.dataType)) {
			LOGGER.warn("文件未找到相应的模板数据类型，文件：{}、解析获得的文件数据类型：{}", new Object[]{this.rawFileName, this.dataType});
			return false;
		}
		
		this.reader = new BufferedReader(new InputStreamReader(stream, "GBK"), 16 * 1024);
		
		// 解析文件头
		this.parseCdtHead(this.reader.readLine());
		
		return true;
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		do {
			if (templet == null || this.reader == null || this.cdtHeadInfo == null) {
				continue;
			}
			
			//TODO:只解析Setup和Releae和ho(5.27)
			if (templet.getDataType() != 32 && templet.getDataType() != 33  && templet.getDataType() != 36) {
				LOGGER.debug("无业务支持，忽略文件:{}", this.rawFileName);
				continue;
			}
			
			try {
				while ((this.lineRecord = this.reader.readLine()) != null) {
					this.lineRecord = this.lineRecord.trim();
					if (this.lineRecord.length() < 1) {
						continue;
					}
					return true;
				}
			} catch (Exception e) {
				LOGGER.error("文件解析异常。文件：{}，异常信息：{}", this.rawFileName, e.getMessage());
				continue;
			}
			
			this.templet = null;
			if (this.reader != null) {
				this.reader.close();
				this.reader = null;
			}
			this.cdtHeadInfo = null;
		} while(parseNextFile());
		
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		// 拆封字段
		List<String> cdtData = swicthLine(',', lineRecord);
		if (cdtData.size() != cdtHeadInfo.length)
			return null;
		
		Map<String, String> recordData = this.createExportPropertyMap(this.templet.getDataType());
		for (int i = 0; i < cdtHeadInfo.length; i++) {
			String fieldIndex = this.cdtFieldNames.get(cdtHeadInfo[i]);
			if (null == fieldIndex || null == cdtData.get(i))
				continue;
			
			String val = cdtData.get(i).trim().replace("\"", "");
			if (val.length() < 1) {
				continue;
			}
			
			recordData.put(fieldIndex, val);
		}
		
		// 时间转换 START_TIME
		Date startTime = this.timeFormatConvertToStr(recordData, "START_TIME");
		
		// 时间转换 RELEASE_TIME
		this.timeFormatConvertToStr(recordData, "RELEASE_TIME");
		// 时间转换 END_TIME
		this.timeFormatConvertToStr(recordData, "END_TIME");
		// 时间转换 SYSTIME
		this.timeFormatConvertToStr(recordData, "SYSTIME");
		// NE_CELL_L表关联获取网元信息。关联字段：VENDOR - ENB_ID - CELL_ID。
		String enbid = recordData.get("ENB_ID");
		String cellid = recordData.get("CELL_ID");
		//String traceid = recordData.get("TRACE_ID");
		LteCellCfgInfo lteCellCfgInfo = LteCellCfgCache.findNeCellByVendorEnbCell(Vendor.VENDOR_ZTE, enbid, cellid);
		if (lteCellCfgInfo != null) {
			/**
			 * 网元回填如下字段 NE_ENB_ID NE_CELL_ID COUNTY_ID COUNTY_NAME CITY_ID CITY_NAME
			 */
			recordData.put("NE_ENB_ID", String.valueOf(lteCellCfgInfo.neEnbId));
			recordData.put("NE_CELL_ID", String.valueOf(lteCellCfgInfo.neCellId));
			recordData.put("COUNTY_ID", String.valueOf(lteCellCfgInfo.countyId));
			recordData.put("COUNTY_NAME", lteCellCfgInfo.countyName);
			recordData.put("CITY_ID", String.valueOf(lteCellCfgInfo.cityId));
			recordData.put("CITY_NAME", lteCellCfgInfo.cityName);
			recordData.put("FDDTDDIND", lteCellCfgInfo.fddTddInd);
			
			/**
			 * 现在中兴的TAC不是TA，定位再没有任何意义，关闭定位.
			 */
			// 添加定位算法(目前zte cdt只有32 33需要定位，且只有ta)
			String ta = null;
			String rsrp = null;
			String ncrsrp = null;
			String pci = null;
			switch (templet.getDataType())
			{
				case 32:
				case 33:
					ta = recordData.get("TAC");
					rsrp = ta;
					ncrsrp = ta;
					pci = recordData.get("PCI");
					
					break;
				case 36:
					//ta = recordData.get("SRC_TA");
					//rsrp = recordData.get("SRC_RSRP");
					//ncrsrp = recordData.get("TGT_RSRP");
					//break;
				case 52:
				case 53:
				case 55:
					break;
				default:
					break;
			}
			
			if (pci != null && (ta != null || rsrp != null || ncrsrp != null)) {
				Double fTA = null, fRSRP = null, fNCRSRP = null;
				Integer nPci = null;
				if (ta != null && ta.length() > 0)
					fTA = Double.parseDouble(ta);
				if (rsrp != null && rsrp.length() > 0)
					fRSRP = Double.parseDouble(rsrp);
				if (ncrsrp != null && ncrsrp.length() > 0)
					fNCRSRP = Double.parseDouble(ncrsrp);
				if (pci != null && pci.length() > 0)
					nPci = Integer.parseInt(pci);
				
				//查找邻区网元
				Long postionParam = LteNeiCellCfgDynamicCache.findNeiCellsByVendorEnbCell(Vendor.VENDOR_ZTE, enbid, cellid);
				// 查找定位信息
				List<LteCellCfgInfo> orientationCells = new LinkedList<LteCellCfgInfo>();
				// 添加主小区
				orientationCells.add(0, lteCellCfgInfo);
				
				if (postionParam != null) {
					int nNECellStartPos = LteNeiCellCfgDynamicCache.NeiCellCache.parsePosParamStart(postionParam);
					int nNeCellCount = LteNeiCellCfgDynamicCache.NeiCellCache.parsePosParamLength(postionParam);
						
					//在邻区中找出pci相等且距离最近的那个邻区
					LteNeiCellCfgInfo minDistanceNeiInfo = null;
					//for (LteNeiCellCfgInfo neiInfo: neiInfoList) {
					for (int offset=0; offset<nNeCellCount; ++offset) {
						LteNeiCellCfgInfo neiInfo = LteNeiCellCfgDynamicCache.neiCellCache.neiCellCfgInfos[nNECellStartPos + offset];
						if (neiInfo.nei_pci == nPci && neiInfo.distance > 0) {
							if (minDistanceNeiInfo == null)
								minDistanceNeiInfo = neiInfo;
							
							if (minDistanceNeiInfo.distance > neiInfo.distance)
								minDistanceNeiInfo = neiInfo;
						}
					}
					
					if (minDistanceNeiInfo != null) {
						orientationCells.add(minDistanceNeiInfo.getCellInfo());
					}
				}
				
				orientation(lteCellCfgInfo, orientationCells, recordData, fTA, fRSRP, fNCRSRP);
			}
		} else if (CommonSystemConfigMgr.isNeNotExistIgnore()) {
			return null;
		} else {
			badWriter.warn("[{}]关联不到网元：VENDOR - ENB_ID - CELL_ID = ZY0804-{}-{}。", new Object[]{this.rawFileName, recordData.get("ENB_ID"),
					recordData.get("CELL_ID")});
		}
		
		if (recordData.get("STAMPTIME") == null) {
			recordData.put("STAMPTIME", dataFormat.format(this.getCurrentDataTime()));
		}
		// 16进制转换成10进制
		if (recordData.get("STMSI") != null) {
			recordData.put("STMSI", Long.toString(Long.parseLong(recordData.get("STMSI"), 16)));
		}
		
		//IMSI MSISDN关联
		if (imsiQueryHelper != null && templet.getDataType() == 32 && startTime != null)
		{
			//String startTime = recordData.get("START_TIME");
			String mmeueS1apid = recordData.get("MME_AP_ID");
			String mtmsi = recordData.get("TMSI");
			String mmegi = recordData.get("MME_GROUP_ID");
			String mmec = recordData.get("MMEC");
			
			// 20150330004754
//			Date dateTimeOfCallConn = null;
//			if (startTime != null && startTime.length() >= 14)
//				dateTimeOfCallConn = dataFormat.parse(startTime);
			
			if (startTime != null
					&& (mtmsi != null || mmeueS1apid != null)
					&& mmegi != null && mmec != null) {
				
				ImsiRequestResult result = imsiQueryHelper.matchIMSIInfo(startTime.getTime(), 
						LteCoreCommonDataManager.converToLong(mmeueS1apid), 
						LteCoreCommonDataManager.converToLong(mtmsi), 
						LteCoreCommonDataManager.converToInteger(mmegi),
						LteCoreCommonDataManager.converToInteger(mmec));
				
				if (result != null 
						&& result.value == ImsiRequestResult.RESPONSE_IMSI_QUERY_SUCCESS) {
					String imsi = String.valueOf(result.imsi);
					recordData.put("IMSI", imsi);
					recordData.put("MSISDN", result.msisdn);
					
					//TODO:不再关联(5.27)
					//mapDataCache.put(traceid, makeKeyValue(imsi, result.msisdn));
				}
			}
		} 
		//TODO:不再关联(5.27)
		/*
		else {
			// MOD_LTETDD_UECAPABILITYINF_ZTE 里面有IMSI，不需要回填, 将无效的imsi,置为NULL
			if (templet.getDataType() == 55) {
				String imsi = recordData.get("IMSI");
				if (imsi != null && imsi.contains("F")) {
					recordData.put("IMSI", null);
				}
				
			} else {
				//String key = makeKeyValue(enbid, cellid, traceid);
				String key = traceid;
				String value = mapDataCache.get(key);
				if (value != null) {
					String[] values = value.split("_");
					if (values.length > 1) {
						recordData.put("IMSI", values[0]);
						recordData.put("MSISDN", values[1]);
					}
				}
			}
		}*/
		
		ParseOutRecord record = new ParseOutRecord();
		record.setType(templet.getDataType());
		record.setRecord(recordData);
		readLineNum++;
		return record;
		//return null;
	}

	/**
	 * 解析话单头信息
	 * 
	 * @throws Exception
	 */
	public final boolean parseCdtHead(String headStr) {
		if (headStr == null || "".equals(headStr.trim())) {
			LOGGER.warn("文件消息头解析异常，文件：{}、消息头信息：", new Object[]{this.rawFileName, headStr});
			return false;
		}
		this.cdtHeadInfo = headStr.toUpperCase().split("\\,");
		return true;
	}

	/**
	 * 找到当前对应的Templet
	 */
	public final boolean findMyTemplet(String className) {
		this.templet = templetMap.get(className);
		if (templet == null) {
			return false;
		}
		this.cdtFieldNames = new HashMap<>();
		for (Field field : this.templet.fieldList) {
			if (field == null) {
				continue;
			}
			this.cdtFieldNames.put(field.getName(), field.getIndex());
		}
		return true;
	}

	/**
	 * 解析文件名
	 * @throws Exception
	 */
	public void parseFileName(String entryFileName) {
		// 文件名：Receiver102804_LTETDD_131012_CDT_2014-08-13_23-59-18_33.zip
		// LTETDD_140915_CDT_264983_2015-03-30_09-00-00_33.csv
		// FDD-LTE_CDT_ZTE_1500_20150401190000_Setup.csv
		// FDD-LTE_CDT_ZTE_V3.2_373058_20160622160000_Setup.csv
		try {
			this.currentDataTime = getZteLteWirelessFileTime(entryFileName);
			int startIndex = entryFileName.lastIndexOf("_");
			if (startIndex == -1) {
				return;
			}
			this.dataType = entryFileName.substring(startIndex + 1, entryFileName.length());
		} catch (Exception e) {
			LOGGER.error("文件名{}，解析文件名异常。{}", entryFileName, e.getMessage());
		}
	}
	
	/**
	 * 从文件名获取时间
	 * 	package: zte_nj2_20150726.0020.zip CDT_ZTE_FDD_193_47_20150828.0000.zip
	 * 	item:	LTETDD_140915_CDT_264998_2015-07-26_00-20-00_33.csv
	 * 			FDD-LTE_CDT_ZTE_300135_20150828000000_UECapability.zip
	 * @param entryFileName
	 * @return
	 */
	public Date getZteLteWirelessFileTime(String entryFileName) {
		Date date = null;
		for (int i=0; i<fileTimePatterns.length; ++i) {
			String patternTime = this.getPattern(fileTimePatterns[i], entryFileName);
			if (patternTime != null) {
				try {
					date = fileDateFormat[i].parse(patternTime);
					return date;
				} catch (ParseException e) {}
			}
		}
				
		return null;
	}
	
	public String getPattern(Pattern pattern, String target) {
		try {
			Matcher matcher = pattern.matcher(target);
			while (matcher.find()) {
				// 只提取第一次满足匹配的字符串
				return matcher.group();
			}
		} catch (Throwable e) {
			// 没找到匹配的字符串
		}
		return null;
	}

	@Override
	public void close() {
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
		LOGGER.debug("[{}]-中兴话单CSV解析，处理{}条记录", new Object[]{task.getId(), readLineNum});
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
		templetMap = csvTempletParser.getTemplets();
	}

	/**
	 * 
	 * @param map
	 * @param fieldName
	 */
	public final Date timeFormatConvertToStr(Map<String, String> recordData, String fieldName) {
		String timeStr = recordData.get(fieldName);
		if (timeStr != null && !"".equals(timeStr.trim())) {
			if (timeStr.length() == 19) {
				// 2016-06-22_16-01-15
				try {
					Date date = dataFormat2.parse(timeStr);
					String dateStr = dataFormat.format(date);
					recordData.put(fieldName, dateStr);
					
					return date;
				} catch (ParseException e) {
					LOGGER.error("时间解析错误，时间解析串:{}", timeStr);
				}
			} else {
				Date date =  new Date(Long.parseLong(timeStr) * 1000 + START_DATA);
				String dateStr = dataFormat.format(date);
				recordData.put(fieldName, dateStr);
				
				return date;
			}
		}
		
		return null;
	}
	
	@Override
	public List<ParseOutRecord> getAllRecords() {
		return null;
	}

	@Override
	public Date getDataTime(ParseOutRecord outRecord) {
		return this.currentDataTime;
	}

	/**
	 * 转换分隔符(,')
	 * 
	 * @param line
	 * @return String 转换后的字符串(没去引号)
	 */
	public List<String> swicthLine(char currSplitSign, String line) {
		List<String> strList = new ArrayList<String>();
		StringBuffer sb = new StringBuffer();
		boolean flag = false;// 双引号标记
		char tmpChar = '0';
		try {
			for (char s : line.toCharArray()) {
				if ((s == currSplitSign) && flag == false) {
					strList.add(sb.toString());
					sb.setLength(0);
					tmpChar = s;
					continue;
				}
				if (s == '\"') {
					if (flag == true) {
						flag = false;
					} else if (tmpChar == currSplitSign || sb.length() == 0) {
						flag = true;
					}
					sb.append(s);
					tmpChar = s;
					continue;
				}
				sb.append(s);
				tmpChar = s;
			}
		} catch (Exception e) {
			LOGGER.debug("switchLine出现异常", e);
		}
		if (sb.toString().length() > 0) {
			strList.add(sb.toString());
		}
		return strList;
	}
	
	public List<SimpleEntry<Long, String>> extractZipStream(InputStream srcInStream, String zipFileName) throws IOException {
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
		
		LOGGER.debug("开始下载文件，从压缩包:{}", zipFileName);
		AdaptiveInputStream in = new AdaptiveInputStream(srcInStream, zipFileName);
		CompressionFileEntry entry = null;
		while ((entry=in.getNextEntry()) != null) {
			String targetFileName = dirname + entry.fileName;
			OutputStream output = null;
			try {
				output = new FileOutputStream(targetFileName);
				IOUtils.copy(entry.inputStream, output);
				output.flush();
				IoUtil.closeQuietly(output);
				
				int enbID = parseEnbID(entry.fileName);
				boolean bIncludeSpecificHeadFileTag = (targetFileName.indexOf(headFileTag1) > 0);
				if (!bIncludeSpecificHeadFileTag) {
					bIncludeSpecificHeadFileTag = (targetFileName.indexOf(headFileTag2) > 0);
				}
				long fileKey = getZteLteWirelessFileTime(targetFileName).getTime();
				if (!bIncludeSpecificHeadFileTag) {
					fileKey = (1L<<62) | ((enbID & 0xFFFFFFL)<<32) | ((fileKey/(60*1000)) & 0xFFFFFFFFL) ;
				} else {
					fileKey = (0L<<62) | ((enbID & 0xFFFFFFL)<<32) | ((fileKey/(60*1000)) & 0xFFFFFFFFL) ;
				}
				
				targetFileList.add(new SimpleEntry<Long, String>(fileKey, targetFileName));
			} catch (Exception e) {
				throw e;
			} finally {
				in.close();
				IoUtil.closeQuietly(output);
			}
		}
		
		Collections.sort(targetFileList, new Comparator<SimpleEntry<Long, String>>() {
			@Override
			public int compare(SimpleEntry<Long, String> arg0, SimpleEntry<Long, String> arg1) {
				return arg0.getKey().compareTo(arg1.getKey());
			}
			
		});
		LOGGER.debug("压缩包下载完成，原文件名：{}，下载目的地：{}, 文件个数：{}", new Object[] {zipFileName, dirname, targetFileList.size()});
		
		return targetFileList;
	}
	
	public String makeKeyValue(String... args) {
		sbKeyValueBuilder.setLength(0);
		for (String arg: args) {
			if (sbKeyValueBuilder.length() > 0)
				sbKeyValueBuilder.append("_");
			
			if (arg != null)
				sbKeyValueBuilder.append(arg);
		}
		
		return sbKeyValueBuilder.toString();
	}
		
	public String getDateString(Date date) {
		if (date == null)
			return null;
		
		return dataFormat.format(date);
	}
	
	/**
	 * 根据主小区、邻小区进行定位计算
	 * @param info 主小区
	 * @param info1 邻小区
	 * @param map
	 */
	protected void orientation(LteCellCfgInfo info,List<LteCellCfgInfo> infoList,Map<String, String> map, 
			Double fRSRP, Double fNCRSRP, Double fTA){
		if (info == null || info.latitude == null || info.longitude == null)
			return;
		
		// 调用定位算法进行定位处理，然后将经纬度进行回填
		LTEOrientUtil p =new LTEOrientUtil(task);
		List<ONEWAYDELAY_CELL> accessCelll = new ArrayList<ONEWAYDELAY_CELL>();
		
		//接入主小区信息
		accessCelll.add(p.getCellInfoType(info, fRSRP, fTA, false));
		// 接入邻小区信息
		if(infoList!=null&&!infoList.isEmpty()){
			for (LteCellCfgInfo ncInfo : infoList) {
				if (ncInfo == null || ncInfo.latitude == null || ncInfo.longitude == null) {
					continue;
				}
				
				accessCelll.add(p.getCellInfoType(ncInfo, (fNCRSRP == null ? fRSRP : fNCRSRP), null, false));
				// 最多加10个邻区
				if (accessCelll.size() > 10)
					break;
			}
		}
		
		LONG_LAT outLL = p.doLocation(DEV_TYPE.LTE_CDR, accessCelll.toArray(new ONEWAYDELAY_CELL[accessCelll.size()]));
		//经、纬度回填
		map.put("LONGITUDE", df.format(outLL.LON));
		map.put("LATITUDE", df.format(outLL.LAT));
		// map.put("LONGITUDE", String.valueOf(outLL.LON));
		// map.put("LATITUDE", String.valueOf(outLL.LAT));
		// 填充栅格信息
		String[] gridInfo = computGridInfo(outLL, info.cityId);
		map.put("GRID_M", gridInfo[0]);
		map.put("GRID_N", gridInfo[1]);
		map.put("GRID_ID", gridInfo[2]);
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
	
	public int parseEnbID(String fileName) {
		String pattern = getPattern(fileNameEnbIDPattern, fileName);
		if (pattern.length() > 1) {
			return Integer.parseInt(pattern.substring(1, 7));
		}
		
		return 0;
	}
	

	public static void main(String[] args) {
//		String entryFile = "FDD-LTE_CDT_ZTE_253952_20160525150000_Setup.csv";
//		Date d = getZteLteWirelessFileTime(fileNameEnbIDPattern, entryFile);
		//String filename = parseFileName(entryFile);
//		System.out.println(d);
//		System.out.println(parseEnbID(entryFile));
	}
}
