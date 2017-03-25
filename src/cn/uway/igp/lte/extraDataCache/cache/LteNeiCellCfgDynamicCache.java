package cn.uway.igp.lte.extraDataCache.cache;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.ZipInputStream;

import org.apache.commons.net.ftp.FTPClient;

import cn.uway.framework.context.AppContext;
import cn.uway.igp.lte.extraDataCache.ExtraDataUtil;
import cn.uway.util.FTPClientUtil;
import cn.uway.util.StringUtil;

/**
 * 邻区关联类(动态)，从邻区表中加载主小区与邻区关系，使用时再与网元表关联
 * @ 2016-2-23
 */
public class LteNeiCellCfgDynamicCache extends Cache {
	/**
	 * 初始化的邻区的大数组初始化最大尺寸(这个可以搞大点，占用内存非常少)
	 */
	private static int nMaxNEICellElement = 16000000;
	
	/**
	 * 这里新搞一个类，主要是为了，在刷新邻区数据时，能保证neiCellCfgInfos数组和neiCellCfgInfoMap的一致性，
	 * 以免正在刷新网元时，数据对象可能存在不一致而出错
	 * @ 2016-2-24
	 */
	public static class NeiCellCache {
		/**
		 * 邻区记录数据组(按L.VENDOR, L.ENB_ID, L.CELL_ID排序)
		 */
		public LteNeiCellCfgInfo[] neiCellCfgInfos;
		
		/**
		 * 主小区与邻区的对应关系映射表
		 * key: 主小区索引(厂家编号(16bit) + eNodeBID(32bit) + cellID(16bit)，以VENDOR - ENB_ID - CELL_ID为唯一小区信息
		 * value:[指向neiCellCfgInfos数组起始位置(32bit)+ 个数(32bit)]
		 */
		public Map<Long, Long> neiCellCfgInfoMap;
		
		public int elementCount = 0;
		
		private long preNeCell = 0;
		private long preNeCellStartPosition = elementCount;
		
		public NeiCellCache() {
			neiCellCfgInfos = new LteNeiCellCfgInfo[nMaxNEICellElement];
			
			// 默认为20万个lte小区
			int nSize = LteCellCfgCache.getNeCellCount();
			if (nSize < 1) {
				nSize = 200000;
			}
			
			neiCellCfgInfoMap = new HashMap<Long, Long>(nSize);
		}
		
		/**
		 * 添加邻区cell信息，cell添加时必须要保证顺序的，以便map表能正确表达到位置
		 * @param neCellKey	小区的key(厂家编号(16bit) + eNodeBID(32bit) + cellID(16bit)，以VENDOR - ENB_ID - CELL_ID为唯一小区信息
		 * @param neiCell
		 */
		public void addNeiCell(long neCellKey, LteNeiCellCfgInfo neiCell) {
			if (preNeCell != neCellKey) {
				if (preNeCell > 0 && elementCount > preNeCellStartPosition) {
					long postionValue = ((preNeCellStartPosition << 32) & 0xFFFFFFFF00000000L) 
							| ((elementCount - preNeCellStartPosition) & 0xFFFFFFFFL);
					
					neiCellCfgInfoMap.put(preNeCell, postionValue);
				}
				preNeCell = neCellKey;
				preNeCellStartPosition = elementCount;
			}
			
			if (neiCell != null) {
				if (elementCount >= nMaxNEICellElement) {
					nMaxNEICellElement += (nMaxNEICellElement * 0.3);
					neiCellCfgInfos = Arrays.copyOf(neiCellCfgInfos, nMaxNEICellElement);
				}
				
				 // minCapacity is usually close to size, so this is a win:
				neiCellCfgInfos[elementCount++] = neiCell;
			}
		}
		
		public static int parsePosParamStart(long postionParam) {
			int nNECellStartPos = (int)((postionParam >> 32) & 0xFFFFFFFFL);
			return nNECellStartPos;
		}
		
		public static int parsePosParamLength(long postionParam) {
			int nNeCellCount = (int)(postionParam & 0xFFFFFFFFL);
			return nNeCellCount;
		}
	}
	
	/**
	 * 邻区缓存
	 */
	public static NeiCellCache neiCellCache = null;
	
	// FTP文件路径
	private static final String NEI_CELL_L_FTP_PATH = "/innerdata/nei/lteNeiCell_simple/*.zip";

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
		//启动时不让其加载，让其使用时延时加载
		//load();
		if (executeThread == null)
			executeThread = new Thread(new LteNeiCellCfgDynamicCache(), "LTE邻区网元加载线程启动");
		//executeThread.start();
	}

	/**
	 * 从内部FTP上下载并加载网元信息
	 */
	public synchronized static void load() {
		LOGGER.debug("LteNeiCellCfgCache：： LTE邻区网元加载开始");
		long startTime = System.currentTimeMillis();
		NeiCellCache currNeiCellCache = null; 
		
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
			currNeiCellCache = new NeiCellCache();
			BufferedReader bufferdReader = null;
			if (StringUtil.isEmpty(neFileEncode) || "${neFileEncode}".equalsIgnoreCase(neFileEncode)) {
				bufferdReader = new BufferedReader(new InputStreamReader(zipInput), 16 * 1024);
			} else {
				bufferdReader = new BufferedReader(new InputStreamReader(zipInput, neFileEncode), 16 * 1024);
			}
			
			String lineInfo = null;
			while ((lineInfo = bufferdReader.readLine()) != null) {
				try {
					createNeEntry(lineInfo, currNeiCellCache);
				} catch (Exception e) {
					LOGGER.warn("nei_cell_l表信息加载失败，失败行：{}", lineInfo);
					throw e;
				}
			}
			
			// 所有加载完成后，添加一条空记录，让cache建立好最后的索引
			currNeiCellCache.addNeiCell(0, null);
			
			long duration = (System.currentTimeMillis() - startTime) / 1000L;
			LOGGER.debug("LTE邻区网元加载完成，共从FTP上加载了{}个网元信息,用时{}秒", new Object[]{currentLoadNum, duration});
			if (lastLoadNum * Cache.factor > currentLoadNum) {
				LOGGER.warn("目前缓存中已有{}个邻区网元，从FTP上加载{}个邻区网元。不满足{}%门限。本次加载邻区网元丢弃", new Object[]{lastLoadNum, currentLoadNum, Cache.factor * 100});
				return;
			}
			lastLoadNum = currentLoadNum;
			currentLoadNum = 0;
			
			// 直接丢弃原来的引用 赋予新的引用地址即可
			// 暂时不要清空，以免有程序对原map正在使用时出错．
//			if (neiCellCfgInfoMap != null) {
//				neiCellCfgInfoMap.clear();
//				neiCellCfgInfoMap = null;
//			}
			
			neiCellCache = currNeiCellCache;
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
	static final LteNeiCellCfgInfo createNeEntry(String lineInfo, NeiCellCache cache) {
		if (lineInfo == null || lineInfo.trim().length() == 0)
			return null;
		
		String[] strs = StringUtil.split(lineInfo.replace("\"", ""), ",");
		LteNeiCellCfgInfo lteNeiCellCfgInfo = new LteNeiCellCfgInfo();
		int valIndex = 0;

		String vendor = null;
		if (strs.length > valIndex && strs[valIndex] != null && strs[valIndex].length()>0)
			vendor = strs[valIndex];
		++valIndex;
		
		String enbID = null;
		if (strs.length > valIndex && strs[valIndex] != null && strs[valIndex].length()>0)
			enbID = strs[valIndex];
		++valIndex;
		
		String cellID = null;
		if (strs.length > valIndex && strs[valIndex] != null && strs[valIndex].length()>0)
			cellID = strs[valIndex];
		++valIndex;
		

		String neiVendor = null;
		if (strs.length > valIndex && strs[valIndex] != null && strs[valIndex].length()>0)
			neiVendor = strs[valIndex];
		++valIndex;
		
		String neiEnbID = null;
		if (strs.length > valIndex && strs[valIndex] != null && strs[valIndex].length()>0)
			neiEnbID = strs[valIndex];
		++valIndex;
		
		String neiCellID = null;
		if (strs.length > valIndex && strs[valIndex] != null && strs[valIndex].length()>0)
			neiCellID = strs[valIndex];
		++valIndex;
		
		long cellKey = LteNeiCellCfgInfo.buildCellInfoKey(vendor, enbID, cellID);
		lteNeiCellCfgInfo.cellInfoKey = LteNeiCellCfgInfo.buildCellInfoKey(neiVendor, neiEnbID, neiCellID);
		
		
//		String IS_MR = strs.length > valIndex ? strs[valIndex] : null;
//		if (IS_MR != null && IS_MR.length()>0) {
//			lteNeiCellCfgInfo.isMR = (IS_MR.equals("1") ? true : false);
//		}
		++valIndex;
		
		String neiPCIValue = strs.length > valIndex ? strs[valIndex] : null;
		if (neiPCIValue != null && neiPCIValue.length()>0) {
			lteNeiCellCfgInfo.nei_pci = Short.valueOf(neiPCIValue);
		}
		++valIndex;
		
		lteNeiCellCfgInfo.distance = strs.length > valIndex ? transferStrToFloat(strs[valIndex]) : 0.0f;
		++valIndex;
				
		++currentLoadNum;
		
		cache.addNeiCell(cellKey, lteNeiCellCfgInfo);
		
		return lteNeiCellCfgInfo;
	}

	/**
	 * 字符串转化为Double
	 * 
	 * @param str
	 * @return
	 */
	public static Float transferStrToFloat(String str) {
		if (StringUtil.isEmpty(str))
			return null;
		return Float.parseFloat(str);
	}
	
	/**
	 * 获取邻区Cell网元信息(根据源小区的enbid和cellid)
	 * 
	 * @param enbId		厂家enbid
	 * @param cellId	厂家cellid
	 * @return 邻区网元信息
	 */
	public static Long findNeiCellsByVendorEnbCell(String vendor, String enbID, String cellID) {
		//让其延时加载
		if (neiCellCache == null) {
			synchronized(executeThread) {
				if (neiCellCache == null) {
					LteNeiCellCfgDynamicCache.load();
					
					// 避免没有邻区时，重复线程启动代码执行
					if (neiCellCache == null) {
						neiCellCache = new NeiCellCache();
					}
					
					// 启动邻区刷新服务
					if (executeThread != null && timer == null) {
						executeThread.start();
					}
				}
			}
		}
		
		if (neiCellCache != null && lastLoadNum < 1)
			return null;
		
		Long cellKey = LteNeiCellCfgInfo.buildCellInfoKey(vendor, enbID, cellID);
		Long param = neiCellCache.neiCellCfgInfoMap.get(cellKey);
		
		return param;
	}
	
	public static boolean isEmpty() {
		return neiCellCache.neiCellCfgInfoMap.isEmpty();
	}

}
