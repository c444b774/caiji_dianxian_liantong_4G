package cn.uway.igp.lte.parser.cdt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.accessor.StreamAccessOutObject;
import cn.uway.framework.context.Vendor;
import cn.uway.framework.log.BadWriter;
import cn.uway.framework.orientation.GridOrientation;
import cn.uway.framework.orientation.OneWayDelayDist;
import cn.uway.framework.orientation.OrientationAPI;
import cn.uway.framework.orientation.Type.CELL_INFO;
import cn.uway.framework.orientation.Type.DEV_TYPE;
import cn.uway.framework.orientation.Type.DiffuseInfo;
import cn.uway.framework.orientation.Type.LONG_LAT;
import cn.uway.framework.orientation.Type.ONEWAYDELAY_CELL;
import cn.uway.framework.parser.AbstractParser;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.templet.CSVCfcTempletParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.framework.task.worker.TaskWorkTerminateException;
import cn.uway.igp.lte.extraDataCache.cache.CityInfo;
import cn.uway.igp.lte.extraDataCache.cache.CityInfoCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgInfo;
import cn.uway.igp.lte.service.AbsImsiQuerySession.ImsiRequestResult;
import cn.uway.igp.lte.service.ImsiQueryHelper;
import cn.uway.igp.lte.service.LteCoreCommonDataManager;
import cn.uway.util.NumberUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class HwCdrCsvParserV200R014C00 extends AbstractParser {

	private static Logger LOGGER = LoggerFactory
			.getLogger(HwCdrCsvParserV200R014C00.class);

	private static final Logger badWriter = BadWriter.getInstance()
			.getBadWriter();

	public StreamAccessOutObject streamOut;

	public String rawFilePath = null; // 要解析的文件

	// 方位角范围，都是120
	private static final int ANGLERANG = 120;

	public String rawFileName;

	public BufferedReader reader = null;

	public String[] cdrHeadInfo = null;
	public String[] cdrHeadFieldIndex = null;

	public HashMap<String, String> cdrFieldNames = null;

	public String lineRecord = null;

	public long readLineNum = 0; // 记录总行数

	// 保留小数点后5位
	public DecimalFormat df = new DecimalFormat(".00000");

	protected ImsiQueryHelper imsiQueryHelper;

	protected Long curr_enb_id;
	
	protected String[] lineValues = new String[2048];

	/**
	 * 未关联到网元的记录数
	 */
	protected long nUnlinkedNeSysRecord = 0;
	
	/**
	 * 无效的记录数
	 */
	protected long nInvalidRecord = 0;

	/**
	 * 时区偏移值(默认偏移8小时)
	 */
	private static final long zoneTimeOffset = 8 * 60 * 60 * 1000L;

	/**
	 * 数据时间和文件时间的有效区间
	 */
	private static final long timeValidRange = 365 * 24 * 60 * 60 * 1000L;

	/**
	 * 华为数据格式(20160320215032)，SimpleDateFormat不要定义成静态变量的,not synchronized. Date
	 * formats are not synchronized. It is recommended to create separate format
	 * instances for each thread. If multiple threads access a format
	 * concurrently, it must be synchronized externally.
	 */
	private final SimpleDateFormat hwCdrTimeFormat = new SimpleDateFormat("yyyyMMddHHmmss");
	
	private final SimpleDateFormat cdrFileTimeFormat = new SimpleDateFormat("yyyyMMddHHmm");
	
	private Date timeCdrStart;

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		if (accessOutObject == null)
			throw new IOException("接入对象无效，null.");
		this.streamOut = (StreamAccessOutObject) accessOutObject;
		this.task = this.streamOut.getTask();
		this.rawFilePath = this.streamOut.getRawAccessName();
		this.startTime = new Timestamp(System.currentTimeMillis()); // 开始解析的时间。
		this.currentDataTime = getHwLteWirelessFileTime(rawFilePath);
		LOGGER.debug("开始解码:{}", this.rawFilePath);
		// 解析模板 获取当前文件对应的templet
		this.parseTemplet();

		if (ImsiQueryHelper.isImsiServerEnable()) {
			imsiQueryHelper = ImsiQueryHelper.getHelperInstance(this.task
					.getId());
			Date fileTime = getHwLteWirelessFileTime(this.rawFilePath);
			if (fileTime != null) {
				ImsiRequestResult result = imsiQueryHelper
						.isCacheReady(fileTime.getTime());
				if (result == null) {
					throw new TaskWorkTerminateException("查询IMSI服务器出错");
				}

				if (result.value != ImsiRequestResult.RESPONSE_CACHE_IS_READY) {
					String errMsg = ImsiRequestResult
							.getResponseValueDesc(result.value);
					LOGGER.debug("{} cdr时间:{}，核心网{}cache最大生成时间{}.",
							new Object[]{
									errMsg,
									getDateString(fileTime),
									result.getRequestServerInfo(),
									getDateString(new Date(result.maxServerTimeInCache))});

					throw new TaskWorkTerminateException(
							"核心网话单未达到关联时间条件或服务器出错, 将在下一个周期继续采集.");
				}
			}
		}

		this.reader = new BufferedReader(new InputStreamReader(
				this.streamOut.getOutObject(), "GBK"), 16 * 1024);
		// 解析文件头
		this.parseCdrHead(this.reader.readLine());
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		if (this.cdrFieldNames == null || this.reader == null
				|| this.currentDataTime == null) {
			return false;
		}
		return getLineData();
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
				this.lineRecord = this.lineRecord.trim();
				if (lineRecord.length() < 1) {
					continue;
				}
				return true;
			}
		} catch (Exception e) {
			LOGGER.error("文件解析异常。文件：{}，异常信息：{}", this.rawFilePath,
					e.getMessage());
			return false;
		}
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		// 拆封字段
		++readLineNum;
		
		int nCsvNum = HwCdtCsvPublicParser.split(this.lineRecord, ',', lineValues);
		if (nCsvNum < 1)
			return null;
		
		Map<String, String> recordData = this.createExportPropertyMap(ParseOutRecord.DEFAULT_DATA_TYPE);
		for (int i = 0; i < cdrHeadInfo.length && i<nCsvNum; i++) {
			if(lineValues[i] == null || lineValues[i].equals("N/A")){
				continue;
			}
			
			String fieldIndex = this.cdrHeadFieldIndex[i];
			if (fieldIndex == null)
				continue;

			if (fieldIndex.equals("TIME_OF_CALL_DROP") || fieldIndex.equals("AC_CALLTIME")) {
				lineValues[i] = lineValues[i].trim();
				if (lineValues[i].length() > 14) {
					lineValues[i] = lineValues[i].substring(0, 14);
				} else if (lineValues[i].length() < 1) {
					continue;
				}
			}

			recordData.put(fieldIndex, lineValues[i]);
		}
		
		try {
			this.timeCdrStart = null;
			if (!conversionProcess(recordData)) {
				++nInvalidRecord;
				return null;
			}
		} catch (Exception e) {
			LOGGER.warn("字段处理异常。原因：", e);
			++nInvalidRecord;
			return null;
		}
		
		// 默认使用采集任务的city_id
		recordData.put("CITY_ID", String.valueOf(this.task.getExtraInfo().getCityId()));
		
		// 如果接入信息未填，只有优先取第一次切换的源ENB_ID/CELL_ID
		String acsEnbID = recordData.get("ENBID");
		String acsCellID = recordData.get("CONNECTION_CELL_ID");
		if (acsEnbID == null || acsEnbID.length() < 1)
			acsEnbID = recordData.get("HF_SRC_ENB_ID");
		if (acsCellID == null || acsCellID.length() < 1)
			acsCellID = recordData.get("HF_SRC_CELL_ID");
		
		// NE_CELL_L表关联获取网元信息。关联字段：VENDOR - E_NODE_ID - CONNECTION_CELL_ID。
		// LteCellCfgInfo lteCellCfgInfo =
		// LteCellCfgCache.findNeCellByVendorEnbCell(Vendor.VENDOR_HW,
		// recordData.get("HF_SRC_ENB_ID"),
		// recordData.get("HF_SRC_CELL_ID"));
		LteCellCfgInfo lteCellCfgInfo = LteCellCfgCache
				.findNeCellByVendorEnbCell(Vendor.VENDOR_HW,
						acsEnbID,
						acsCellID);
		if (lteCellCfgInfo != null) {
			/**
			 * 网元回填如下字段 NE_ENB_ID NE_CELL_ID COUNTY_ID COUNTY_NAME CITY_ID
			 * CITY_NAME FDDTDDIND
			 */
			recordData.put("NE_ENB_ID", String.valueOf(lteCellCfgInfo.neEnbId));
			recordData.put("NE_CELL_ID",
					String.valueOf(lteCellCfgInfo.neCellId));
			recordData
					.put("COUNTY_ID", String.valueOf(lteCellCfgInfo.countyId));
			recordData.put("COUNTY_NAME", lteCellCfgInfo.countyName);
			recordData.put("CITY_ID", String.valueOf(lteCellCfgInfo.cityId));
			recordData.put("CITY_NAME", lteCellCfgInfo.cityName);
			recordData.put("FDDTDDIND", lteCellCfgInfo.fddTddInd);
//		} else if (CommonSystemConfigMgr.isNeNotExistIgnore()) {
//			 badWriter.warn("[{}]关联不到网元：VENDOR - ENB_ID - CELL_ID = ZY0808-{}-{}。",
//			 new Object[]{this.rawFileName, recordData.get("E_NODE_ID"),
//			 recordData.get("CONNECTION_CELL_ID")});
		} else {
			++nUnlinkedNeSysRecord;
		}
		if (lteCellCfgInfo != null && lteCellCfgInfo.longitude != null
				&& lteCellCfgInfo.latitude != null) {
			// 接入定位
			List<ONEWAYDELAY_CELL> accessCellf = new ArrayList<ONEWAYDELAY_CELL>();
			ONEWAYDELAY_CELL infos = new ONEWAYDELAY_CELL();
			infos.cell_info = new CELL_INFO();
			// strength W网使用的，LTE不需要，先按以前写的，搞个默认值
			infos.strength = "-255";
			// 定位库优先使用one_way_delay作TA计算距离
			// String sTA = recordData.get("AC_TA");
			// infot.one_way_delay = (sTA==null || sTA.length()<1) ? 0.0f :
			// NumberUtil.parseFloat(sTA, 0.0f);
			infos.one_way_delay = 0;
			infos.cell_info.LatLong = new LONG_LAT(lteCellCfgInfo.longitude,
					lteCellCfgInfo.latitude, 0);
			infos.cell_info.Angle = lteCellCfgInfo.direct_angle == null
					? 0f
					: lteCellCfgInfo.direct_angle.floatValue();
			infos.cell_info.AngleRang = ANGLERANG;
			infos.cell_info.CellType = OrientationAPI
					.convertCellTypeLTE(lteCellCfgInfo.location_type);
			infos.cell_info.Coverage_area = OrientationAPI
					.convertCoverageType(lteCellCfgInfo.coverage_area);
			infos.cell_info.antenna_high = lteCellCfgInfo.antenna_high == null
					? 30f
					: lteCellCfgInfo.antenna_high.floatValue();
			infos.cell_info.dl_ear_fcn = changeDlEarFun(lteCellCfgInfo.dl_ear_fcn);
			infos.rscp = NumberUtil.parseFloat(recordData.get("HF_SRC_RSRP"),
					0f);
			// 由于网元无覆盖范围信息，暂定给10000米
			infos.cell_info.Radius = 10000f;
			accessCellf.add(infos);
			// 接入邻区信息(第一次切换的目标，为邻区算)
			LteCellCfgInfo lteCellCfgInfot = LteCellCfgCache
					.findNeCellByVendorEnbCell(Vendor.VENDOR_HW,
							recordData.get("HF_TGT_ENB_ID"),
							recordData.get("HF_TGT_CELL_ID"));
			if (lteCellCfgInfot != null && lteCellCfgInfot.longitude != null
					&& lteCellCfgInfot.latitude != null) {
				ONEWAYDELAY_CELL infot = new ONEWAYDELAY_CELL();
				infot.cell_info = new CELL_INFO();
				// strength W网使用的，LTE不需要，先按以前写的，搞个默认值
				infos.strength = "-255";
				// 定位库优先使用one_way_delay作TA计算距离
				// String sTA = recordData.get("AC_TA");
				// infot.one_way_delay = (sTA==null || sTA.length()<1) ? 0.0f :
				// NumberUtil.parseFloat(sTA, 0.0f);
				infot.one_way_delay = 0.0f;
				infot.cell_info.LatLong = new LONG_LAT(
						lteCellCfgInfot.longitude, lteCellCfgInfot.latitude, 0);
				infot.cell_info.Angle = lteCellCfgInfot.direct_angle == null
						? 0f
						: lteCellCfgInfot.direct_angle.floatValue();
				infot.cell_info.AngleRang = ANGLERANG;
				infot.cell_info.CellType = OrientationAPI
						.convertCellTypeLTE(lteCellCfgInfot.location_type);
				infot.cell_info.Coverage_area = OrientationAPI
						.convertCoverageType(lteCellCfgInfot.coverage_area);
				infot.cell_info.antenna_high = lteCellCfgInfot.antenna_high == null
						? 30f
						: lteCellCfgInfot.antenna_high.floatValue();
				infot.cell_info.dl_ear_fcn = changeDlEarFun(lteCellCfgInfot.dl_ear_fcn);
				infot.rscp = NumberUtil.parseFloat(
						recordData.get("HF_TGT_RSRP"), 0f);
				// 由于网元无覆盖范围信息，暂定给10000米
				infot.cell_info.Radius = 10000f;
				accessCellf.add(infot);
			}
			LONG_LAT location = doLocation(
					DEV_TYPE.LTE_CDR,
					accessCellf.toArray(new ONEWAYDELAY_CELL[accessCellf.size()]));

			recordData.put("LONGITUDE", df.format(location.LON));
			recordData.put("LATITUDE", df.format(location.LAT));
			// 填充栅格信息
			String[] gridInfo = computGridInfo(location, lteCellCfgInfo.cityId);
			recordData.put("GRID_M", gridInfo[0]);
			recordData.put("GRID_N", gridInfo[1]);
			recordData.put("GRID_ID", gridInfo[2]);
		}

		// 释放定位
		// LteCellCfgInfo lastLteCellCfgInfo =
		// LteCellCfgCache.findNeCellByVendorEnbCell(Vendor.VENDOR_HW,
		// recordData.get("HL_SRC_ENB_ID"),
		// recordData.get("HL_SRC_CELL_ID"));
		LteCellCfgInfo lastLteCellCfgInfo = LteCellCfgCache
				.findNeCellByVendorEnbCell(Vendor.VENDOR_HW,
						recordData.get("RLS_ENODEBID"),
						recordData.get("RELEASE_CELL_ID"));
		if (lastLteCellCfgInfo != null && lastLteCellCfgInfo.longitude != null
				&& lastLteCellCfgInfo.latitude != null) {
			List<ONEWAYDELAY_CELL> accessCelll = new ArrayList<ONEWAYDELAY_CELL>();
			ONEWAYDELAY_CELL lastinfos = new ONEWAYDELAY_CELL();
			lastinfos.cell_info = new CELL_INFO();
			// strength W网使用的，LTE不需要，先按以前写的，搞个默认值
			lastinfos.strength = "-255";
			// 定位库优先使用one_way_delay作TA计算距离
			String sTA = recordData.get("RLS_TA");
			lastinfos.one_way_delay = (sTA == null || sTA.length() < 1)
					? 0.0f
					: NumberUtil.parseFloat(sTA, 0.0f);
			lastinfos.cell_info.LatLong = new LONG_LAT(
					lastLteCellCfgInfo.longitude, lastLteCellCfgInfo.latitude,
					0);
			lastinfos.cell_info.Angle = lastLteCellCfgInfo.direct_angle == null
					? 0f
					: lastLteCellCfgInfo.direct_angle.floatValue();
			lastinfos.cell_info.AngleRang = ANGLERANG;
			lastinfos.cell_info.CellType = OrientationAPI
					.convertCellTypeLTE(lastLteCellCfgInfo.location_type);
			lastinfos.cell_info.Coverage_area = OrientationAPI
					.convertCoverageType(lastLteCellCfgInfo.coverage_area);
			lastinfos.cell_info.antenna_high = lastLteCellCfgInfo.antenna_high == null
					? 30f
					: lastLteCellCfgInfo.antenna_high.floatValue();
			lastinfos.cell_info.dl_ear_fcn = changeDlEarFun(lastLteCellCfgInfo.dl_ear_fcn);
			lastinfos.rscp = NumberUtil.parseFloat(
					recordData.get("HL_TGT_RSRP"), 0f);
			// 由于网元无覆盖范围信息，暂定给10000米
			lastinfos.cell_info.Radius = 10000f;
			accessCelll.add(lastinfos);
			// 释放邻区信息(最一次切换的源小区，为邻区算)
			LteCellCfgInfo lastLteCellCfgInfot = LteCellCfgCache
					.findNeCellByVendorEnbCell(Vendor.VENDOR_HW,
							recordData.get("HL_SRC_ENB_ID"),
							recordData.get("HL_SRC_CELL_ID"));
			if (lastLteCellCfgInfot != null
					&& lastLteCellCfgInfot.longitude != null
					&& lastLteCellCfgInfot.latitude != null) {
				ONEWAYDELAY_CELL lastinfot = new ONEWAYDELAY_CELL();
				lastinfot.cell_info = new CELL_INFO();
				// strength W网使用的，LTE不需要，先按以前写的，搞个默认值
				lastinfot.strength = "-255";
				// 定位库优先使用one_way_delay作TA计算距离
				// String sTA = recordData.get("AC_TA");
				// infot.one_way_delay = (sTA==null || sTA.length()<1) ? 0.0f :
				// NumberUtil.parseFloat(sTA, 0.0f);
				lastinfot.one_way_delay = 0.0f;
				lastinfot.cell_info.LatLong = new LONG_LAT(
						lastLteCellCfgInfot.longitude,
						lastLteCellCfgInfot.latitude, 0);
				lastinfot.cell_info.Angle = lastLteCellCfgInfot.direct_angle == null
						? 0f
						: lastLteCellCfgInfot.direct_angle.floatValue();
				lastinfot.cell_info.AngleRang = ANGLERANG;
				lastinfot.cell_info.CellType = OrientationAPI
						.convertCellTypeLTE(lastLteCellCfgInfot.location_type);
				lastinfot.cell_info.Coverage_area = OrientationAPI
						.convertCoverageType(lastLteCellCfgInfot.coverage_area);
				lastinfot.cell_info.antenna_high = lastLteCellCfgInfot.antenna_high == null
						? 30f
						: lastLteCellCfgInfot.antenna_high.floatValue();
				lastinfot.cell_info.dl_ear_fcn = changeDlEarFun(lastLteCellCfgInfot.dl_ear_fcn);
				lastinfot.rscp = NumberUtil.parseFloat(
						recordData.get("HL_SRC_RSRP"), 0f);
				// 由于网元无覆盖范围信息，暂定给10000米
				lastinfot.cell_info.Radius = 10000f;
				accessCelll.add(lastinfot);
			}

			LONG_LAT lastlocation = doLocation(
					DEV_TYPE.LTE_CDR,
					accessCelll.toArray(new ONEWAYDELAY_CELL[accessCelll.size()]));

			recordData.put("RELEASE_LONGITUDE", df.format(lastlocation.LON));
			recordData.put("RELEASE_LATITUDE", df.format(lastlocation.LAT));

			// 填充栅格信息
			String[] lastgridInfo = computGridInfo(lastlocation,
					lastLteCellCfgInfo.cityId);
			recordData.put("RELEASE_GRID_M", lastgridInfo[0]);
			recordData.put("RELEASE_GRID_N", lastgridInfo[1]);
			recordData.put("RELEASE_GRID_ID", lastgridInfo[2]);
		}

		// IMSI MSISDN关联
		String cdrStartTime = recordData.get("CDR_STARTTIME");
		String mmeueS1apid = recordData.get("MMEUES1APID");
		String stmsi = recordData.get("S_TMSI");

		String mmegi = recordData.get("AC_MMEGROUPID");
		String mmec = recordData.get("AC_MMECODE");

		// 从S_TMSI中切出MTmsi，Mmec: 1 bytes， MTmsi: 4 bytes
		String mtmsi = null;
		if (stmsi != null && stmsi.length() > 0) {
			// 数值类型 16进制 10进制
			if (stmsi.startsWith("0x")) {
				mtmsi = Long.valueOf(stmsi.substring(2), 16).toString();
			} else {
				mtmsi = Long.valueOf(stmsi, 10).toString();
			}
		}

		// 判断下记录的有效性
		if (timeCdrStart != null
				&& Math.abs((currentDataTime.getTime() - timeCdrStart.getTime())) > timeValidRange) {
			badWriter.warn("非法文件时间记录.CDR_STARTTIME{}, record:{}", cdrStartTime,
					this.lineRecord);
			++nInvalidRecord;
			return null;
		}

		if (imsiQueryHelper != null && timeCdrStart != null
				&& (mtmsi != null || mmeueS1apid != null) && mmegi != null
				&& mmec != null) {

			ImsiRequestResult result = imsiQueryHelper.matchIMSIInfo(
					timeCdrStart.getTime()/* + zoneTimeOffset */,
					LteCoreCommonDataManager.converToLong(mmeueS1apid),
					LteCoreCommonDataManager.converToLong(mtmsi),
					LteCoreCommonDataManager.converToInteger(mmegi),
					LteCoreCommonDataManager.converToInteger(mmec));

			if (result != null
					&& result.value == ImsiRequestResult.RESPONSE_IMSI_QUERY_SUCCESS) {
				String imsi = String.valueOf(result.imsi);
				recordData.put("IMSI", imsi);
				recordData.put("MSISDN", result.msisdn);
			}
		}

		ParseOutRecord record = new ParseOutRecord();
		record.setType(ParseOutRecord.DEFAULT_DATA_TYPE);
		record.setRecord(recordData);
		// return null;
		return record;
	}

	// 导频强度由强到若 大到小
	public class CellStrengthComparator implements Comparator<ONEWAYDELAY_CELL> {

		@Override
		public int compare(ONEWAYDELAY_CELL o1, ONEWAYDELAY_CELL o2) {
			if (o1.strength == null && o2.strength == null)
				return 0;
			else if (o1.strength == null)
				return -1;
			else if (o2.strength == null)
				return -1;

			BigDecimal firstWeight = new BigDecimal(o1.strength);
			BigDecimal secondWeight = new BigDecimal(o2.strength);
			return secondWeight.compareTo(firstWeight);
		}
	}

	/**
	 * 频点数据 需要转换
	 * 
	 * @param dl_ear_fcn
	 * @return
	 */
	public float changeDlEarFun(Double dl_ear_fcn) {
		if (dl_ear_fcn == null) {
			return 2.1f;
		} else if (dl_ear_fcn <= 599) {
			return 2.1f;
		} else if (dl_ear_fcn <= 1949) {
			return 1.8f;
		} else if (dl_ear_fcn <= 9039) {
			return 0.8f;
		} else {
			return 2.6f;
		}
	}

	protected final LONG_LAT doLocation(DEV_TYPE devType,
			ONEWAYDELAY_CELL[] cellInfo) {
		LONG_LAT outLL = new LONG_LAT();
		// Arrays.sort(cellInfo, new CellStrengthComparator());
		try {
			OrientationAPI.doLocation_LTE(cellInfo, new OneWayDelayDist(
					devType, DiffuseInfo.DEFAULT_VAL), outLL);
		} catch (Exception e) {
			LOGGER.error("定位失败，taskId：{}，bsc：{}，cells：{}", new Object[]{
					this.task.getId(), this.task.getExtraInfo().getBscId(),
					(cellInfo != null ? Arrays.asList(cellInfo) : "<null>")});
		}
		return outLL;
	}

	/**
	 * 数据处理
	 * 
	 * @param recordData
	 */
	private boolean conversionProcess(Map<String, String> recordData)
			throws Exception {
		this.timeCdrStart = processHwCdrTime(recordData, "CDR_STARTTIME");
		Date timeCdrEnd = processHwCdrTime(recordData, "CDR_ENDTIME");
		if (timeCdrStart != null && timeCdrEnd != null) {
			// 精确到秒
			long time = (timeCdrEnd.getTime() - timeCdrStart.getTime()) / 1000;
			recordData.put("TIME_OF_CALL_CONNECTION", String.valueOf(time));
		}
		
		processHwCdrTime(recordData, "RELEASETIME");
		processHwCdrTime(recordData, "HF_STARTTIME");
		processHwCdrTime(recordData, "HF_ENDTIME");
		processHwCdrTime(recordData, "HL_STARTTIME");
		processHwCdrTime(recordData, "HL_ENDTIME");

		return timeCdrStart != null || timeCdrEnd != null;
	}

	/**
	 * 解析话单头信息
	 * 
	 * @throws Exception
	 */
	public final boolean parseCdrHead(String headStr) {
		if (headStr == null || "".equals(headStr.trim())) {
			LOGGER.warn("文件消息头解析异常，文件：{}、消息头信息：", new Object[]{
					this.rawFilePath, headStr});
			return false;
		}
		this.cdrHeadInfo = StringUtil.split(headStr.toUpperCase(), ",");
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
	 * 从文件名获取时间 package: CSL027LTE-8.64.125.40-001-01-001-M-201507311120.zip
	 * item: CSL027LTE-8.64.125.40-001-01-001-M-201507311120.csv
	 * 
	 * @param entryFileName
	 * @return
	 */
	public Date getHwLteWirelessFileTime(String entryFileName) {
		String patternTime = entryFileName.substring(
				entryFileName.lastIndexOf("-") + 1,
				entryFileName.lastIndexOf("."));
		Date date = null;
		if (patternTime != null) {
			try {
				date = cdrFileTimeFormat.parse(patternTime);
				return date;
			} catch (ParseException e) {
			}
		}
		return null;
	}

	/**
	 * 转换cdr的时间点，加上时区
	 * 
	 * @param recordData
	 * @param fieldName
	 */
	public Date processHwCdrTime(Map<String, String> recordData,
			String fieldName) {
		String value = recordData.get(fieldName);
		if (value == null)
			return null;

		value = value.trim();
		if (value.length() < 14) {
			if (value.length() == 0 || value.equals("0")) {
				recordData.put(fieldName, null);
			}

			return null;
		}

		try {
			Date d = hwCdrTimeFormat.parse(value);
			d.setTime(d.getTime() + zoneTimeOffset);
			value = hwCdrTimeFormat.format(d);
			recordData.put(fieldName, value);

			return d;
		} catch (Exception e) {
			LOGGER.debug(fieldName + " is error format date ,value = " + value);
		}
		return null;
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("【华为解析统计】[{}]-华为cdr解析，处理{}条记录, 无效的记录数：{}, 未关联到网元的共{}条记录.",
				new Object[]{task.getId(), readLineNum, nInvalidRecord, nUnlinkedNeSysRecord});
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
		return GridOrientation.orientationGridInfo(longitude, latitude,
				cityInfo.longLt, cityInfo.longRt, cityInfo.latLt,
				cityInfo.latRt, cityInfo.getGridM(), cityInfo.getGridN());
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
}
