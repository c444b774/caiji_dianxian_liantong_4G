package cn.uway.igp.lte.util;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.orientation.GridOrientation;
import cn.uway.framework.orientation.Type.DEV_TYPE;
import cn.uway.framework.orientation.Type.LONG_LAT;
import cn.uway.framework.orientation.Type.ONEWAYDELAY_CELL;
import cn.uway.framework.task.Task;
import cn.uway.igp.lte.context.common.CommonSystemConfigMgr;
import cn.uway.igp.lte.extraDataCache.cache.CityInfo;
import cn.uway.igp.lte.extraDataCache.cache.CityInfoCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgInfo;
import cn.uway.igp.lte.extraDataCache.cache.LteNeiCellCfgDynamicCache;
import cn.uway.igp.lte.extraDataCache.cache.LteNeiCellCfgInfo;
import cn.uway.util.StringUtil;

/**
 * 中兴，华为，诺西，贝尔话单关联网元与定位工具类
 * @author huzq
 *
 */
public class AssociateCellUtil {
	private static Logger LOGGER = LoggerFactory.getLogger(AssociateCellUtil.class);
	/**value = value-140**/
	private static final String[] filePlus140 = {"AC_MR_LTENCRSRP1","AC_MR_LTENCRSRP2","AC_MR_LTENCRSRP3","AC_MR_LTENCRSRP4","AC_MR_LTENCRSRP5","AC_MR_LTENCRSRP6",
		"RLS_MR_LTENCRSRP1","RLS_MR_LTENCRSRP2","RLS_MR_LTENCRSRP3","RLS_MR_LTENCRSRP4","RLS_MR_LTENCRSRP5","RLS_MR_LTENCRSRP6",
		"HO_FIRST_SRCRSRP","HO_LAST_SRCRSRP","HO_FIRST_TGTRSRP","HO_LAST_TGTRSRP","HO_FIRST_MR_LTENCRSRP1","HO_FIRST_MR_LTENCRSRP2",
		"HO_FIRST_MR_LTENCRSRP3","HO_FIRST_MR_LTENCRSRP4","HO_FIRST_MR_LTENCRSRP5","HO_FIRST_MR_LTENCRSRP6","HO_LAST_MR_LTENCRSRP1",
		"HO_LAST_MR_LTENCRSRP2","HO_LAST_MR_LTENCRSRP3","HO_LAST_MR_LTENCRSRP4","HO_LAST_MR_LTENCRSRP5","HO_LAST_MR_LTENCRSRP6",
		"RLF_DLRSRP","AC_MR_LTESCRSRP","RLS_MR_LTESCRSRP"};
	
	/**
	 * value = -20+value/2
	 */
	private static final String[] filedPlus20AddHalf = {"AC_MR_LTENCRSRQ1","AC_MR_LTENCRSRQ2","AC_MR_LTENCRSRQ3","AC_MR_LTENCRSRQ4","AC_MR_LTENCRSRQ5","AC_MR_LTENCRSRQ6",
		"RLS_MR_LTENCRSRQ1","RLS_MR_LTENCRSRQ2","RLS_MR_LTENCRSRQ3","RLS_MR_LTENCRSRQ4","RLS_MR_LTENCRSRQ5","RLS_MR_LTENCRSRQ6",
		"HO_FIRST_SRCRSRQ","HO_LAST_SRCRSRQ","HO_FIRST_TGTRSRQ","HO_LAST_TGTRSRQ","HO_FIRST_MR_LTENCRSRQ1","HO_FIRST_MR_LTENCRSRQ2",
		"HO_FIRST_MR_LTENCRSRQ3","HO_FIRST_MR_LTENCRSRQ4","HO_FIRST_MR_LTENCRSRQ5","HO_FIRST_MR_LTENCRSRQ6","HO_LAST_MR_LTENCRSRQ1",
		"HO_LAST_MR_LTENCRSRQ2","HO_LAST_MR_LTENCRSRQ3","HO_LAST_MR_LTENCRSRQ4","HO_LAST_MR_LTENCRSRQ5","HO_LAST_MR_LTENCRSRQ6","RLF_DLRSRQ",
		"AC_MR_LTESCRSRQ","RLS_MR_LTESCRSRQ"};
	
	// 保留小数点后5位
	 private static final ThreadLocal<DecimalFormat> df = new ThreadLocal<DecimalFormat>() {

	        @Override
	        protected DecimalFormat initialValue() {
	            return new DecimalFormat("0.00000");
	        }
	    };

//	private static final DecimalFormat df = new DecimalFormat("0.00000");
	
	private static final Map<String,String> lteNcRSRPMap = new HashMap<String,String>();
	static
	{
		lteNcRSRPMap.put("AC_MR_LTENCRSRP1", "AC_MR_LTENCPCI1");
		lteNcRSRPMap.put("AC_MR_LTENCRSRP2", "AC_MR_LTENCPCI2");
		lteNcRSRPMap.put("AC_MR_LTENCRSRP3", "AC_MR_LTENCPCI3");
		lteNcRSRPMap.put("AC_MR_LTENCRSRP4", "AC_MR_LTENCPCI4");
		lteNcRSRPMap.put("AC_MR_LTENCRSRP5", "AC_MR_LTENCPCI5");
		lteNcRSRPMap.put("AC_MR_LTENCRSRP6", "AC_MR_LTENCPCI6");
	}
	
	private static final Map<String,String> rlsLteNcRSRPMap = new HashMap<String,String>();
	static
	{
		rlsLteNcRSRPMap.put("RLS_MR_LTENCRSRP1", "RLS_MR_LTENCPCI1");
		rlsLteNcRSRPMap.put("RLS_MR_LTENCRSRP2", "RLS_MR_LTENCPCI2");
		rlsLteNcRSRPMap.put("RLS_MR_LTENCRSRP3", "RLS_MR_LTENCPCI3");
		rlsLteNcRSRPMap.put("RLS_MR_LTENCRSRP4", "RLS_MR_LTENCPCI4");
		rlsLteNcRSRPMap.put("RLS_MR_LTENCRSRP5", "RLS_MR_LTENCPCI5");
		rlsLteNcRSRPMap.put("RLS_MR_LTENCRSRP6", "RLS_MR_LTENCPCI6");
	}
	
	private static final Map<String,String> enbidCellid = new HashMap<String,String>();
	static
	{
		enbidCellid.put("HO_FIRST_SRCENBID", "HO_FIRST_SRCCELLID");
		enbidCellid.put("HO_FIRST_TGTENBID", "HO_FIRST_TGTCELLID");
		enbidCellid.put("HO_LAST_SRCENBID", "HO_LAST_SRCCELLID");
		enbidCellid.put("HO_LAST_TGTENBID", "HO_LAST_TGTCELLID");
	}
	
	/**
	 *回填网元信息
	 * @param recordData
	 * @return   网元关联不上返回false，关联上返回true
	 */
	public static boolean associateCellInfo(Task task,Map<String, String> recordData,int nMinOrientNeiCellsNumber,String vendor){
		boolean isCellNull = true;
		LteCellCfgInfo lteCellCfgInfo = LteCellCfgCache.findNeCellByVendorEnbCell(vendor, recordData.get("CDR_ENODEBID"),
				recordData.get("AC_CELLID"));
		Long postionParam = null;
		LTEOrientUtil p = new LTEOrientUtil(task);
		if (lteCellCfgInfo != null) {
			/**
			 * 网元回填如下字段 NE_ENB_ID NE_CELL_ID COUNTY_ID COUNTY_NAME CITY_ID CITY_NAME FDDTDDIND
			 */
			recordData.put("NE_ENB_ID", String.valueOf(lteCellCfgInfo.neEnbId));
			recordData.put("NE_CELL_ID", String.valueOf(lteCellCfgInfo.neCellId));
			recordData.put("COUNTY_ID", String.valueOf(lteCellCfgInfo.countyId));
			recordData.put("COUNTY_NAME", lteCellCfgInfo.countyName);
			recordData.put("CITY_ID", StringUtils.isEmpty(String.valueOf(lteCellCfgInfo.cityId)) ? "0":String.valueOf(lteCellCfgInfo.cityId));
			recordData.put("CITY_NAME", lteCellCfgInfo.cityName);
			recordData.put("FDDTDDIND", lteCellCfgInfo.fddTddInd);
			/**
			 * 位置精准化平台需求用到  ,位置上报类型
			 */
			if(StringUtils.isNotEmpty(lteCellCfgInfo.location_type) && 
					lteCellCfgInfo.location_type.equals("室内")){
				recordData.put("LOC_TYPE", "11");
			}else if(StringUtils.isNotEmpty(lteCellCfgInfo.location_type) && 
					lteCellCfgInfo.location_type.equals("室外")){
				recordData.put("LOC_TYPE", "6");
			}
			
			//查找邻区网元
			postionParam = LteNeiCellCfgDynamicCache.findNeiCellsByVendorEnbCell(vendor, 
					recordData.get("CDR_ENODEBID"), recordData.get("AC_CELLID"));
			
			//定位处理
			
			List<ONEWAYDELAY_CELL> accessCelll = new ArrayList<ONEWAYDELAY_CELL>();
			{
				Double fRsrp = null;
				Double fTA = null;
				//这里原来为"MR.LTESCRSRQ"，后确认需要改为"MR.LTESCRSRP".
				//String value = ensureNotNullValue(this.vLableMrDataRecordMap.get("MR.LTESCRSRQ"));
				String value = ensureNotNullValue(recordData.get("AC_MR_LTESCRSRP"));
				if (value.length() > 0)
					fRsrp = Double.parseDouble(value);
				
				value = ensureNotNullValue(recordData.get("AC_MR_LTESCTADV"));
				if (value.length() > 0)
					fTA = Double.parseDouble(value);
				
				ONEWAYDELAY_CELL mainOnwayDelayCell = createOnwayDelayCell(p, lteCellCfgInfo, fRsrp, fTA);
				if (mainOnwayDelayCell != null) {
					accessCelll.add(mainOnwayDelayCell);
				} 
			}
			
			// 添加邻区的定位参数信息
			if (postionParam != null) {
				int nNECellStartPos = LteNeiCellCfgDynamicCache.NeiCellCache.parsePosParamStart(postionParam);
				int nNeCellCount = LteNeiCellCfgDynamicCache.NeiCellCache.parsePosParamLength(postionParam);
				for (Map.Entry<String, String> entry : lteNcRSRPMap.entrySet()) {   
					String value = ensureNotNullValue(recordData.get(entry.getKey()));
					if (value.length() < 1)
						continue;
					Double fNcRsrp = Double.parseDouble(value);
					
					value = ensureNotNullValue(recordData.get(entry.getValue()));
					if (value.length() < 1)
						continue;
					Integer ncPCI = Integer.parseInt(value);
					
					if (fNcRsrp == null || ncPCI == null)
						continue;
					
					//在邻区中找出pci相等且距离最近的那个邻区
					LteNeiCellCfgInfo minDistanceNeiInfo = null;
					//for (LteNeiCellCfgInfo neiInfo: neiInfoList) {
					for (int offset=0; offset<nNeCellCount; ++offset) {
						LteNeiCellCfgInfo neiInfo = LteNeiCellCfgDynamicCache.neiCellCache.neiCellCfgInfos[nNECellStartPos + offset];
						if (neiInfo.nei_pci == ncPCI && neiInfo.distance > 0) {
							if (minDistanceNeiInfo == null)
								minDistanceNeiInfo = neiInfo;
							
							if (minDistanceNeiInfo.distance > neiInfo.distance)
								minDistanceNeiInfo = neiInfo;
						}
					}
					
					if (minDistanceNeiInfo != null) {
						ONEWAYDELAY_CELL onwayDelayCell = createOnwayDelayCell(p, minDistanceNeiInfo.getCellInfo(), fNcRsrp, null);
						if (onwayDelayCell != null) {
							accessCelll.add(onwayDelayCell);
						}
					}
				}
			}
			
			// 定位
			if (accessCelll.size() > 0 
					&& (nMinOrientNeiCellsNumber < 1 || accessCelll.size() >= (nMinOrientNeiCellsNumber+1))) {
				orientation(p, lteCellCfgInfo, accessCelll, recordData);
			}
			
			
			// 释放定位
			// NE_CELL_L表关联获取网元信息。关联字段：VENDOR - E_NODE_ID - CONNECTION_CELL_ID。
			LteCellCfgInfo lastlteCellCfgInfo = LteCellCfgCache.findNeCellByVendorEnbCell(vendor, recordData.get("RLS_ENBID"),
					recordData.get("RLS_CELLID"));
			Long lastpostionParam = null;
			if (lastlteCellCfgInfo != null) {
				//查找邻区网元
				lastpostionParam = LteNeiCellCfgDynamicCache.findNeiCellsByVendorEnbCell(vendor, 
						recordData.get("RLS_ENBID"), recordData.get("RLS_CELLID"));
				
				//定位处理
				List<ONEWAYDELAY_CELL> lastAccessCelll = new ArrayList<ONEWAYDELAY_CELL>();
				{
					Double fRsrp = null;
					Double fTA = null;
					//这里原来为"MR.LTESCRSRQ"，后确认需要改为"MR.LTESCRSRP".
					//String value = ensureNotNullValue(this.vLableMrDataRecordMap.get("MR.LTESCRSRQ"));
					String value = ensureNotNullValue(recordData.get("RLS_MR_LTESCRSRP"));
					if (value.length() > 0)
						fRsrp = Double.parseDouble(value);
					
					value = ensureNotNullValue(recordData.get("RLS_MR_LTESCTADV"));
					if (value.length() > 0)
						fTA = Double.parseDouble(value);
					
					ONEWAYDELAY_CELL mainOnwayDelayCell = createOnwayDelayCell(p, lastlteCellCfgInfo, fRsrp, fTA);
					if (mainOnwayDelayCell != null) {
						lastAccessCelll.add(mainOnwayDelayCell);
					} 
				}
				// 添加邻区的定位参数信息
				if (lastpostionParam != null) {
					int nNECellStartPos = LteNeiCellCfgDynamicCache.NeiCellCache.parsePosParamStart(lastpostionParam);
					int nNeCellCount = LteNeiCellCfgDynamicCache.NeiCellCache.parsePosParamLength(lastpostionParam);
					for (Map.Entry<String, String> entry : rlsLteNcRSRPMap.entrySet()) {   
						String value = ensureNotNullValue(recordData.get(entry.getKey()));
						if (value.length() < 1)
							continue;
						Double fNcRsrp = Double.parseDouble(value);
						
						value = ensureNotNullValue(recordData.get(entry.getValue()));
						if (value.length() < 1)
							continue;
						Integer ncPCI = Integer.parseInt(value);
						
						if (fNcRsrp == null || ncPCI == null)
							continue;
						
						//在邻区中找出pci相等且距离最近的那个邻区
						LteNeiCellCfgInfo minDistanceNeiInfo = null;
						//for (LteNeiCellCfgInfo neiInfo: neiInfoList) {
						for (int offset=0; offset<nNeCellCount; ++offset) {
							LteNeiCellCfgInfo neiInfo = LteNeiCellCfgDynamicCache.neiCellCache.neiCellCfgInfos[nNECellStartPos + offset];
							if (neiInfo.nei_pci == ncPCI && neiInfo.distance > 0) {
								if (minDistanceNeiInfo == null)
									minDistanceNeiInfo = neiInfo;
								
								if (minDistanceNeiInfo.distance > neiInfo.distance)
									minDistanceNeiInfo = neiInfo;
							}
						}
						
						if (minDistanceNeiInfo != null) {
							ONEWAYDELAY_CELL onwayDelayCell = createOnwayDelayCell(p, minDistanceNeiInfo.getCellInfo(), fNcRsrp, null);
							if (onwayDelayCell != null) {
								lastAccessCelll.add(onwayDelayCell);
							}
						}
					}
				}
				
				// 定位
				if (lastAccessCelll.size() > 0 
						&& (nMinOrientNeiCellsNumber < 1 || lastAccessCelll.size() >= (nMinOrientNeiCellsNumber+1))) {
					lastOrientation(p, lastlteCellCfgInfo, lastAccessCelll, recordData);
				}
			}
		} else if (!CommonSystemConfigMgr.isNeNotExistIgnore()) {
			isCellNull = false;
		}
//		else{
//			LOGGER.debug("关联网元关败:VENDOR={},CDR_ENODEBID={},AC_CELLID={}", new Object[]{Vendor.VENDOR_HW, 
//					recordData.get("CDR_ENODEBID"),recordData.get("AC_CELLID")});
//		}
		return isCellNull;
	}
	
	
	
	/**
	 * 接入与释放都关联不上city_id，在切换与最后切换时再关联一次
	 * @return
	 */
	public static void associateCityId(Map<String, String> recordData,String vendor){
		for (Map.Entry<String, String> entry : enbidCellid.entrySet()) {
			String enbid  = recordData.get(entry.getKey());
			if(StringUtils.isEmpty(enbid)){
				continue;
			}
			String cellid = recordData.get(entry.getValue());
			LteCellCfgInfo lteCellCfgInfo = LteCellCfgCache.findNeCellByVendorEnbCell(vendor, enbid,
					cellid);
			if(null != lteCellCfgInfo && lteCellCfgInfo.cityId > 0){
				recordData.put("CITY_ID", String.valueOf(lteCellCfgInfo.cityId));
				recordData.put("CITY_NAME", lteCellCfgInfo.cityName);
				break ;
			}
		}
	}
	
	
	protected static ONEWAYDELAY_CELL createOnwayDelayCell(LTEOrientUtil p, LteCellCfgInfo ncInfo, Double fRSRP, Double fTA) {
		if (ncInfo == null || ncInfo.latitude == null || ncInfo.longitude == null) {
			return null;
		}
		
		return p.getCellInfoType(ncInfo, fRSRP, fTA, false);
	}
	
	/**
	 * 根据主小区、邻小区进行定位计算
	 * @param info 主小区
	 * @param info1 邻小区
	 * @param map
	 */
	private static void orientation(LTEOrientUtil p, LteCellCfgInfo ncInfo, 
			List<ONEWAYDELAY_CELL> accessCelll, Map<String, String> map){
		LONG_LAT outLL = p.doLocation(DEV_TYPE.LTE_MR, accessCelll.toArray(new ONEWAYDELAY_CELL[accessCelll.size()]));
		//经、纬度回填
		map.put("LONGITUDE", df.get().format(outLL.LON));
		map.put("LATITUDE", df.get().format(outLL.LAT));
		// map.put("LONGITUDE", String.valueOf(outLL.LON));
		// map.put("LATITUDE", String.valueOf(outLL.LAT));
		// 填充栅格信息
		String[] gridInfo = computGridInfo(outLL, ncInfo.cityId);
		map.put("GRID_M", gridInfo[0]);
		map.put("GRID_N", gridInfo[1]);
		map.put("GRID_ID", gridInfo[2]);
	}
	
	/**
	 * 根据主小区、邻小区进行定位计算
	 * @param info 主小区
	 * @param info1 邻小区
	 * @param map
	 */
	private static void lastOrientation(LTEOrientUtil p, LteCellCfgInfo ncInfo, 
			List<ONEWAYDELAY_CELL> accessCelll, Map<String, String> map){
		LONG_LAT outLL = p.doLocation(DEV_TYPE.LTE_MR, accessCelll.toArray(new ONEWAYDELAY_CELL[accessCelll.size()]));
		map.put("RELEASE_LONGITUDE", df.get().format(outLL.LON));
		map.put("RELEASE_LATITUDE", df.get().format(outLL.LAT));
		if(StringUtils.isEmpty(map.get("CITY_ID"))){
			map.put("CITY_ID", String.valueOf(ncInfo.cityId));
			map.put("CITY_NAME",ncInfo.cityName);
		}
		// 填充栅格信息
		String[] lastgridInfo = computGridInfo(outLL, ncInfo.cityId);
		map.put("RELEASE_GRID_M", lastgridInfo[0]);
		map.put("RELEASE_GRID_N", lastgridInfo[1]);
		map.put("RELEASE_GRID_ID", lastgridInfo[2]);
	}
	
	
	/**
	 * 通过经度、维度信息计算栅格
	 * 
	 * @param outLL
	 * @return 栅格信息
	 */
	private static String[] computGridInfo(LONG_LAT outLL, int cityId) {
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
	
	public static String ensureNotNullValue(String value) {
		if (value == null)
			return "";
		
		value = value.trim();
		
		return value;
	}
	
	
	
	public static void conversionFieldPlus140(Map<String, String> recordData){
		for(String fieldName : filePlus140){
			String value = recordData.get(fieldName);
		    int intValue = 0;
			if (StringUtils.isNotBlank(value)  && StringUtil.isNum(value)) {
				intValue = Integer.parseInt(value);
				intValue -= 140;
					recordData.put(fieldName, String.valueOf(intValue));
			}
		}
	}
	
	public static void conversionFieldPlus20Addhalf(Map<String, String> recordData){
		for(String fieldName : filedPlus20AddHalf){
			String value = recordData.get(fieldName);
		    int intValue = 0;
			if (StringUtils.isNotBlank(value)  && StringUtil.isNum(value)) {
				intValue = Integer.parseInt(value);
				intValue = intValue/2-20;
					recordData.put(fieldName, String.valueOf(intValue));
				
			}
		}
	}
	
	public static void conversion_MR_LteScPHR(Map<String, String> recordData){
			Integer aPHR = null;
			Integer RPHR = null;
			String acPhr = recordData.get("AC_MR_LTESCPHR");
			String rslPhr = recordData.get("RLS_MR_LTESCPHR");
			if (StringUtils.isNotBlank(acPhr)  && StringUtil.isNum(acPhr)) {
				aPHR = Integer.parseInt(acPhr);
				if (aPHR != null) {
					aPHR = aPHR-23;
					recordData.put("AC_MR_LTESCPHR", aPHR.toString());
				}
			}
			if (StringUtils.isNotBlank(rslPhr)  && StringUtil.isNum(rslPhr)) {
				RPHR = Integer.parseInt(rslPhr);
				if (RPHR != null) {
					RPHR = RPHR-23;
					recordData.put("RLS_MR_LTESCPHR", RPHR.toString());
				}
			}
	}
	

}
