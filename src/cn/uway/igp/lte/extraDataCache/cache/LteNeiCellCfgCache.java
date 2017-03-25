package cn.uway.igp.lte.extraDataCache.cache;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.ZipInputStream;

import org.apache.commons.net.ftp.FTPClient;

import cn.uway.framework.context.AppContext;
import cn.uway.igp.lte.extraDataCache.ExtraDataUtil;
import cn.uway.util.FTPClientUtil;
import cn.uway.util.StringUtil;

public class LteNeiCellCfgCache extends Cache {

	/**
	 * LTE网Cell信息，索引字段 以VENDOR - ENB_ID - CELL_ID为唯一小区信息
	 */
	public static Map<String, List<LteCellCfgInfo>> neiCellCfgInfoMap = new HashMap<String, List<LteCellCfgInfo>>();
	
	/**
	 * LTE网Cell信息，索引字段 以VENDOR - NE_ENB_ID - NE_CELL_ID为唯一小区信息
	 */	
	public static Map<String, List<LteCellCfgInfo>> neiNeCellCfgInfoMap = new HashMap<String, List<LteCellCfgInfo>>();

	// FTP文件路径
	private static final String NEI_CELL_L_FTP_PATH = "/innerdata/nei/lteNeiCell/*.zip";

	// FTP文件编码
	private static String neFileEncode = AppContext.getBean("neFileEncode", String.class);

	/**
	 * 定时器
	 */
	private static Timer timer;

	/**
	 * 定时执行线程
	 */
	private static Thread executeThread;

	/**
	 * 上次加载的条数 用于进行门限判断
	 */
	private static int lastLoadNum = 0;

	/**
	 * 本次加载的条数
	 */
	private static int currentLoadNum = 0;

	@Override
	public void run() {
		LOGGER.debug("LTE邻区网元加载线程启动。");
		timer = new Timer("LTE_NEI_CELL Timmer");
		timer.schedule(new ReloadTimerTask(), period, period);
	}

	class ReloadTimerTask extends TimerTask {

		public void run() {
			load();
		}
	}

	public synchronized static void startLoad() {
		load();
		if (executeThread == null)
			executeThread = new Thread(new LteNeiCellCfgCache(), "LTE邻区网元加载线程启动");
		executeThread.start();
	}

	/**
	 * 从内部FTP上下载并加载网元信息
	 */
	public synchronized static void load() {
		LOGGER.debug("LteNeiCellCfgCache：： LTE邻区网元加载开始");
		long startTime = System.currentTimeMillis();
		Map<String, List<LteCellCfgInfo>> currentNeiCellCfgInfoMap = null;
		Map<String, List<LteCellCfgInfo>> currentNeiNeCellCfgInfoMap = null;
		ZipInputStream zipInput = null;
		InputStream inputstream = null;
		FTPClient client = null;
		try {
			client = FTPClientUtil.connectFTP(connectionInfo);
			inputstream = ExtraDataUtil.retriveFile(client, NEI_CELL_L_FTP_PATH);
			if (inputstream == null) {
				return;
			}
			zipInput = new ZipInputStream(inputstream);
			// 只有一个entry所以先getNextEntry
			if (zipInput.getNextEntry() == null)
				return;
			currentNeiCellCfgInfoMap = new HashMap<String, List<LteCellCfgInfo>>();
			currentNeiNeCellCfgInfoMap = new HashMap<String, List<LteCellCfgInfo>>();
			BufferedReader bufferdReader = null;// new BufferedReader(new InputStreamReader(zipInput, "gbk"), 16 * 1024);
			if (StringUtil.isEmpty(neFileEncode) || "${neFileEncode}".equalsIgnoreCase(neFileEncode)) {
				bufferdReader = new BufferedReader(new InputStreamReader(zipInput), 16 * 1024);
			} else {
				bufferdReader = new BufferedReader(new InputStreamReader(zipInput, neFileEncode), 16 * 1024);
			}
			String lineInfo = null;
			while ((lineInfo = bufferdReader.readLine()) != null) {
				try {
					createNeEntry(currentNeiCellCfgInfoMap, currentNeiNeCellCfgInfoMap, lineInfo);
				} catch (Exception e) {
					LOGGER.warn("nei_cell_l表信息加载失败，失败行：{}", lineInfo);
					throw e;
				}
			}
			long duration = (System.currentTimeMillis() - startTime) / 1000L;
			LOGGER.debug("LTE邻区网元加载完成，共从FTP上加载了{}个网元信息,用时{}秒", new Object[]{currentLoadNum, duration});
			if (lastLoadNum * Cache.factor > currentLoadNum) {
				LOGGER.warn("目前缓存中已有{}个邻区网元，从FTP上加载{}个邻区网元。不满足{}%门限。本次加载邻区网元丢弃", new Object[]{lastLoadNum, currentLoadNum, Cache.factor * 100});
				return;
			}
			lastLoadNum = currentLoadNum;
			currentLoadNum = 0;
			
			// 直接丢弃原来的引用 赋予新的引用地址即可
			if (neiCellCfgInfoMap != null) {
				neiCellCfgInfoMap.clear();
				neiCellCfgInfoMap = null;
			}
			neiCellCfgInfoMap = currentNeiCellCfgInfoMap;
			
			if (neiNeCellCfgInfoMap != null) {
				neiNeCellCfgInfoMap.clear();
				neiNeCellCfgInfoMap = null;
			}
			neiNeCellCfgInfoMap = currentNeiNeCellCfgInfoMap;
			
			
		} catch (Exception e) {
			LOGGER.error("LTE邻区网元加载异常", e);
		} finally {
			ExtraDataUtil.closeFtpStream(zipInput, inputstream, client);
		}
	}

	/**
	 * 解析一行网元信息 并且添加到MAP中
	 * 
	 * @param outElement
	 * @param lineInfo
	 */
	static final void createNeEntry(Map<String, List<LteCellCfgInfo>> currNeiCellMap, Map<String, List<LteCellCfgInfo>> currNeiNeCellMap, String lineInfo) {
		if (lineInfo == null || lineInfo.trim().length() == 0)
			return;
		// String[] strs = lineInfo.split(",");
		// if (strs.length < 8)
		// return;
		
		String[] strs = StringUtil.split(lineInfo.replace("\"", ""), ",");
		LteCellCfgInfo lteCellCfgInfo = new LteCellCfgInfo();
		
		int valIndex = 2;
		lteCellCfgInfo.cityId = Integer.valueOf(strs[valIndex++]);
		lteCellCfgInfo.cityName = strs[valIndex++];
		lteCellCfgInfo.neEnbId = strs[valIndex++];
		lteCellCfgInfo.neCellId = strs[valIndex++];
		
		if (!"".equals(strs[valIndex].trim())) {
			lteCellCfgInfo.countyId = Integer.valueOf(strs[valIndex]);
		}
		valIndex++;
		
		lteCellCfgInfo.countyName = strs[valIndex++];
		lteCellCfgInfo.fddTddInd = strs[valIndex++];
		lteCellCfgInfo.longitude = strs.length > valIndex ? transferStrToDouble(strs[valIndex++]) : null;
		lteCellCfgInfo.latitude = strs.length > valIndex ? transferStrToDouble(strs[valIndex++]) : null;
		lteCellCfgInfo.direct_angle = strs.length > valIndex ? transferStrToDouble(strs[valIndex++]) : null;
		lteCellCfgInfo.antenna_high = strs.length > valIndex ? transferStrToDouble(strs[valIndex++]) : null;
		lteCellCfgInfo.location_type = strs.length > valIndex ? strs[valIndex++] : null;
		lteCellCfgInfo.dl_ear_fcn = strs.length > valIndex ? transferStrToDouble(strs[valIndex++]) : null;
		lteCellCfgInfo.coverage_area = strs.length > valIndex ? strs[valIndex++] : null;
		String IS_MR = strs.length > valIndex ? strs[valIndex++] : null;
		if (IS_MR != null && IS_MR.length()>0) {
			lteCellCfgInfo.isMR = (IS_MR.equals("1") ? true : false);
		}
		String neiPCIValue = strs.length > valIndex ? strs[valIndex++] : null;
		if (neiPCIValue != null && neiPCIValue.length()>0) {
			lteCellCfgInfo.nei_pci = Short.valueOf(neiPCIValue);
		}
		lteCellCfgInfo.distance = strs.length > valIndex ? transferStrToDouble(strs[valIndex++]) : null;
				
		if (strs[0] !=null && strs[0].length()>0) {
			List<LteCellCfgInfo> cellList = currNeiCellMap.get(strs[0]);
			if (cellList == null) {
				cellList = new LinkedList<LteCellCfgInfo>();
				currNeiCellMap.put(strs[0], cellList);
			}
			
			cellList.add(lteCellCfgInfo);
		}
		
		if (strs[1] !=null && strs[1].length()>0) {
			List<LteCellCfgInfo> cellList = currNeiNeCellMap.get(strs[0]);
			if (cellList == null) {
				cellList = new LinkedList<LteCellCfgInfo>();
				currNeiNeCellMap.put(strs[1], cellList);
			}
			
			cellList.add(lteCellCfgInfo);
		}
		
		++currentLoadNum;
	}

	/**
	 * 字符串转化为Double
	 * 
	 * @param str
	 * @return
	 */
	public static Double transferStrToDouble(String str) {
		if (StringUtil.isEmpty(str))
			return null;
		return Double.parseDouble(str);
	}
	
	/**
	 * 获取邻区Cell网元信息(根据源小区的enbid和cellid)
	 * 
	 * @param enbId		厂家enbid
	 * @param cellId	厂家cellid
	 * @return 邻区网元信息
	 */
	public static List<LteCellCfgInfo> findNeCellLByVendorEnbCells(String vendor, String enbId, String cellId) {
		if (lastLoadNum == 0)
			return null;
		return neiCellCfgInfoMap.get(new StringBuilder().append(vendor).append("-").append(enbId).append("-").append(cellId).toString());
	}
	
	/**
	 * 获取邻区Cell网元信息(根据源小区的ne_enbid和necellid)
	 * @param vendor	厂家编号
	 * @param neEnbId 	uway内部enbid
	 * @param neCellId	uway内部cellid
	 * @return	邻区网元信息
	 */
	public static List<LteCellCfgInfo> findNeCellLByVendorNeEnbCells(String vendor, String neEnbId, String neCellId) {
		if (lastLoadNum == 0)
			return null;
		return neiNeCellCfgInfoMap.get(new StringBuilder().append(vendor).append("-").append(neEnbId).append("-").append(neCellId).toString());
	}

	public static boolean isEmpty() {
		return neiCellCfgInfoMap.isEmpty() && neiNeCellCfgInfoMap.isEmpty();
	}

}
