package cn.uway.igp.lte.util;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.orientation.OneWayDelayDist;
import cn.uway.framework.orientation.OrientationAPI;
import cn.uway.framework.orientation.Type.CELL_INFO;
import cn.uway.framework.orientation.Type.DEV_TYPE;
import cn.uway.framework.orientation.Type.DiffuseInfo;
import cn.uway.framework.orientation.Type.LONG_LAT;
import cn.uway.framework.orientation.Type.ONEWAYDELAY_CELL;
import cn.uway.framework.task.Task;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgInfo;
import cn.uway.util.StringUtil;

/**
 * LTE定位解析类
 * 
 * @author tylerlee @ 2015年8月25日
 */
public class LTEOrientUtil {

	private static Logger LOGGER = LoggerFactory.getLogger(LTEOrientUtil.class);

	private Task task;

	public LTEOrientUtil(Task task) {
		this.task = task;
	}

	// 方位角范围，都是120
	private static final int ANGLERANG = 120;

	
	public ONEWAYDELAY_CELL getCellInfoType(LteCellCfgInfo lteCellCfgInfo, Map<String, String> recordData, String strengthKey) {
		ONEWAYDELAY_CELL infos = new ONEWAYDELAY_CELL();
		infos.cell_info = new CELL_INFO();
		String rscp = recordData.get(strengthKey);
		if (StringUtil.isEmpty(rscp)) {
			//TODO  此分支的具体算法还需要看文档确定；
			infos.strength = "-255";
			infos.rscp = -140;
		} else {
			infos.strength = recordData.get(strengthKey);
			infos.rscp = Float.parseFloat(rscp) - 140;
		}
		infos.cell_info.LatLong = new LONG_LAT(lteCellCfgInfo.longitude, lteCellCfgInfo.latitude, 0);
		infos.cell_info.Angle = lteCellCfgInfo.direct_angle == null ? 0f : lteCellCfgInfo.direct_angle.floatValue();
		infos.cell_info.AngleRang = ANGLERANG;
		infos.cell_info.CellType = OrientationAPI.convertCellTypeLTE(lteCellCfgInfo.location_type);
		infos.cell_info.Coverage_area = OrientationAPI.convertCoverageType(lteCellCfgInfo.coverage_area);
		infos.cell_info.antenna_high = lteCellCfgInfo.antenna_high == null ? 30f : lteCellCfgInfo.antenna_high.floatValue();
		infos.cell_info.dl_ear_fcn = changeDlEarFun(lteCellCfgInfo.dl_ear_fcn);
		infos.one_way_delay = 0;
		// 由于网元无覆盖范围信息，暂定给10000米
		infos.cell_info.Radius = 10000f;

		return infos;
	}
	
	/**
	 * 
	 * @param lteCellCfgInfo	lte网元对象
	 * @param rsrp				rsrp值
	 * @param ta				ta值
	 * @param bSubtractRsrp		rsrp是否减去140
	 * @return
	 */
	public ONEWAYDELAY_CELL getCellInfoType(LteCellCfgInfo lteCellCfgInfo, Double rsrp, Double ta, boolean bSubtractRsrp140) {
		ONEWAYDELAY_CELL infos = new ONEWAYDELAY_CELL();
		infos.cell_info = new CELL_INFO();
		
		//strength W网使用的，LTE不需要，先按以前写的，搞个默认值
		infos.strength = "-255";	
		// 定位库优先使用one_way_delay作TA计算距离
		infos.one_way_delay = (float)(ta == null? 0 : ta);
		// 定位库当one_way_delay为０时，使用rsrp计算距离
		infos.rscp = (float)(rsrp == null ? 0 : rsrp) - (bSubtractRsrp140 ? 140 : 0);
		
		infos.cell_info.LatLong = new LONG_LAT(lteCellCfgInfo.longitude, lteCellCfgInfo.latitude, 0);
		infos.cell_info.Angle = lteCellCfgInfo.direct_angle == null ? 0f : lteCellCfgInfo.direct_angle.floatValue();
		infos.cell_info.AngleRang = ANGLERANG;
		infos.cell_info.CellType = OrientationAPI.convertCellTypeLTE(lteCellCfgInfo.location_type);
		infos.cell_info.Coverage_area = OrientationAPI.convertCoverageType(lteCellCfgInfo.coverage_area);
		infos.cell_info.antenna_high = lteCellCfgInfo.antenna_high == null ? 30f : lteCellCfgInfo.antenna_high.floatValue();
		infos.cell_info.dl_ear_fcn = changeDlEarFun(lteCellCfgInfo.dl_ear_fcn);
		// 由于网元无覆盖范围信息，暂定给10000米
		infos.cell_info.Radius = 10000f;

		return infos;
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

	/**
	 * 进行定位算法的调用
	 * @param devType 厂家数据类型
	 * @param cellInfo 网元相关数据
	 * @return 定位信息
	 */
	public LONG_LAT doLocation(DEV_TYPE devType, ONEWAYDELAY_CELL[] cellInfo) {
		LONG_LAT outLL = new LONG_LAT();
		//Arrays.sort(cellInfo, new CellStrengthComparator());
		try {
			OrientationAPI.doLocation_LTE(cellInfo, new OneWayDelayDist(devType, DiffuseInfo.DEFAULT_VAL), outLL);
		} catch (Exception e) {
			LOGGER.error("MR数据定位失败，taskId：{}，bsc：{}，cells：{}", new Object[]{this.task.getId(), this.task.getExtraInfo().getBscId(),
					(cellInfo != null ? Arrays.asList(cellInfo) : "<null>")});
		}
		return outLL;
	}

	// 导频强度由强到若 大到小
	public class CellStrengthComparator implements Comparator<ONEWAYDELAY_CELL> {

		@Override
		public int compare(ONEWAYDELAY_CELL o1, ONEWAYDELAY_CELL o2) {
			BigDecimal firstWeight = new BigDecimal(o1.strength);
			BigDecimal secondWeight = new BigDecimal(o2.strength);
			return secondWeight.compareTo(firstWeight);
		}
	}
}