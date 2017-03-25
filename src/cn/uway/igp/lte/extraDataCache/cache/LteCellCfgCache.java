package cn.uway.igp.lte.extraDataCache.cache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.ZipInputStream;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import cn.uway.framework.context.AppContext;
import cn.uway.igp.lte.extraDataCache.ExtraDataUtil;
import cn.uway.util.FTPClientUtil;
import cn.uway.util.FTPUtil;
import cn.uway.util.StringUtil;

public class LteCellCfgCache extends Cache {

	/**
	 * LTE网Cell信息，索引字段 以VENDOR - ENB_ID - CELL_ID为唯一小区信息
	 */
	public static volatile Map<String, LteCellCfgInfo> cellCfgInfoMap = null;

	// FTP文件路径
	private static final String NE_CELL_L_FTP_PATH = "/innerdata/ne/lteCell/*.zip";

	// FTP文件编码
	private static String neFileEncode = AppContext.getBean("neFileEncode", String.class);
	
	//当天日期，如果是当天就是已经加载过网元，如果不是就是当天还没有加载当天的网元
	public String currDate="1970-01-01";

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
		LOGGER.debug("LTE网元加载线程启动");
		//网元刷新间隔，默认为5秒,可在config.ini里配置 ne_refresh_interval
		FTPClient client = null;
		FTPFile[] ftpFiles=null;
		int sleepSecond=5;
		int heartbeat =0;
		String neRefreshInterval=null;
		try{
			Thread.sleep(5*1000);
			neRefreshInterval=AppContext.getBean("neRefreshInterval", String.class);
			if(!StringUtil.isEmpty(neRefreshInterval) && !neRefreshInterval.startsWith("${sys")){
				sleepSecond=Integer.valueOf(neRefreshInterval);
				LOGGER.debug("网元文件时间检查间隔为{}秒一次",sleepSecond);
			}else{
				LOGGER.debug("网元文件时间检查间隔默认是{}秒一次",sleepSecond);
			}
		} catch (InterruptedException e1) {
			LOGGER.debug("LTE网元加载线程第一次检查ftp文件时间前,休眠interrupted!");
		}catch(Exception e){
			LOGGER.debug("网元刷新检查间隔system.ne.refresh.interval配置不是数字,使用默认值,{}秒检查一次",sleepSecond);
		}
		SimpleDateFormat sdf=new SimpleDateFormat("yyyy-MM-dd");
		SimpleDateFormat sdfFileModify=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		String filePath = null;
		String fileLastModifyTime="1970-01-01 00:00:00";
		boolean fileModified=false;
		currDate=sdf.format(new Date());
		while(true){
			try {
				if(!currDate.equals(sdf.format(new Date())) || fileModified){
					load(); 
					if(fileModified){
						LOGGER.debug("网元文件被修改,重新加载,修改时间为‘{}’",fileLastModifyTime);
					}
					currDate=sdf.format(new Date());
					fileModified=false;
				}
				if(heartbeat%10==0){
					if(!fileLastModifyTime.equals("1970-01-01 00:00:00")){
						LOGGER.debug("上一轮检查的最后一次为{}",fileLastModifyTime);
					}
					heartbeat=0;
				}
				heartbeat++;
				Thread.sleep(sleepSecond*1000);
				// 检查ftp上是否产生了最新的网元文件或最新的文件有修改记录,修改过要重新加载网元
				//获取ftp连接
				client = FTPClientUtil.connectFTP(connectionInfo);
				filePath=FTPUtil.getOrderTopFile(client, NE_CELL_L_FTP_PATH,null);
				ftpFiles = client.listFiles(StringUtil.encodeFTPPath(filePath, ""));
				if(ftpFiles.length>=1){
					//文件早先的修改时间与文件后来的修改时间比较
					Date FileNewTime=ftpFiles[0].getTimestamp().getTime();
					Date FileOldTime=sdfFileModify.parse(fileLastModifyTime);
					if(!fileLastModifyTime.equals(sdfFileModify.format(FileNewTime))){
						if(FileOldTime.getTime()<FileNewTime.getTime()){
							fileLastModifyTime=sdfFileModify.format(FileNewTime);
							fileModified=true;
						}
					}
				}else{
					LOGGER.debug("服务器上无当前最新匹配文件,remotePath= " +filePath);
				}
				//XXX 释放ftp连接  
				FTPUtil.logoutAndCloseFTPClient(client);
			} catch (InterruptedException e) {
				LOGGER.debug("线程休眠时被外部打断: ",e.getMessage());
			}catch (IOException e1) {
				LOGGER.debug("在服务器'{}'上检查最新网元文件的修改时间时错误" ,connectionInfo.getIp());
			}catch (ParseException e2) {
				LOGGER.debug("解析文件修改时间错误");
			}catch (Exception e3) {
				LOGGER.debug("连接ftp时错误");
			}
		}
	}
	
	public synchronized static void startLoad() {
		//启动时不让其加载，让其使用时延时加载
		//load();
		if (executeThread == null)
			executeThread = new Thread(new LteCellCfgCache(), "LTE网元加载线程");
		//executeThread.start();
	}

	/**
	 * 从内部FTP上下载并加载网元信息
	 */
	public synchronized static void load() {
		LOGGER.debug("LteCellCfgCache：： LTE网元加载开始");
		long startTime = System.currentTimeMillis();
		Map<String, LteCellCfgInfo> currentCellCfgInfoMap = null;
		ZipInputStream zipInput = null;
		InputStream inputstream = null;
		FTPClient client = null;
		try {
			client = FTPClientUtil.connectFTP(connectionInfo);
			inputstream = ExtraDataUtil.retriveFile(client, NE_CELL_L_FTP_PATH);
			if (inputstream == null) {
				return;
			}
			zipInput = new ZipInputStream(inputstream);
			// 只有一个entry所以先getNextEntry
			if (zipInput.getNextEntry() == null)
				return;
			currentCellCfgInfoMap = new HashMap<String, LteCellCfgInfo>();
			BufferedReader bufferdReader = null;// new BufferedReader(new InputStreamReader(zipInput, "gbk"), 16 * 1024);
			if (StringUtil.isEmpty(neFileEncode) || "${neFileEncode}".equalsIgnoreCase(neFileEncode)) {
				bufferdReader = new BufferedReader(new InputStreamReader(zipInput), 16 * 1024);
			} else {
				bufferdReader = new BufferedReader(new InputStreamReader(zipInput, neFileEncode), 16 * 1024);
			}
			String lineInfo = null;
			while ((lineInfo = bufferdReader.readLine()) != null) {
				try {
					createNeEntry(currentCellCfgInfoMap, lineInfo);
				} catch (Exception e) {
					LOGGER.warn("ne_cell_l表信息加载失败，失败行：{}", lineInfo);
					throw e;
				}
			}
			long duration = (System.currentTimeMillis() - startTime) / 1000L;
			LOGGER.debug("LTE网元加载完成，共从FTP上加载了{}个网元信息,用时{}秒", new Object[]{currentLoadNum, duration});
			if (lastLoadNum * Cache.factor > currentLoadNum) {
				LOGGER.warn("目前缓存中已有{}个网元，从FTP上加载{}个网元。不满足{}%门限。本次加载网元丢弃", new Object[]{lastLoadNum, currentLoadNum, Cache.factor * 100});
				return;
			}
			lastLoadNum = currentLoadNum;
			currentLoadNum = 0;
			// 直接丢弃原来的引用 赋予新的引用地址即可
			cellCfgInfoMap = null;
			cellCfgInfoMap = currentCellCfgInfoMap;
		} catch (Exception e) {
			LOGGER.error("LTE网元加载异常", e);
		} finally {
			ExtraDataUtil.closeFtpStream(zipInput, inputstream, client);
		}
		
		if (cellCfgInfoMap == null) {
			cellCfgInfoMap = new HashMap<String, LteCellCfgInfo>();
		}
	}

	/**
	 * 解析一行网元信息 并且添加到MAP中
	 * 
	 * @param outElement
	 * @param lineInfo
	 */
	static final void createNeEntry(Map<String, LteCellCfgInfo> currentLoadNeCellW, String lineInfo) {
		if (lineInfo == null || lineInfo.trim().length() == 0)
			return;
		// String[] strs = lineInfo.split(",");
		// if (strs.length < 8)
		// return;
		String[] strs = StringUtil.split(lineInfo.replace("\"", ""), ",");
		LteCellCfgInfo lteCellCfgInfo = new LteCellCfgInfo();
		lteCellCfgInfo.cityId = Integer.valueOf(strs[1]);
		lteCellCfgInfo.cityName = strs[2];
		lteCellCfgInfo.neEnbId = strs[3];
		lteCellCfgInfo.neCellId = strs[4];
		if (!"".equals(strs[5].trim()))
			lteCellCfgInfo.countyId = Integer.valueOf(strs[5]);
		lteCellCfgInfo.countyName = strs[6];
		lteCellCfgInfo.fddTddInd = strs[7];
		lteCellCfgInfo.longitude = strs.length > 8 ? transferStrToDouble(strs[8]) : null;
		lteCellCfgInfo.latitude = strs.length > 9 ? transferStrToDouble(strs[9]) : null;
		lteCellCfgInfo.direct_angle = strs.length > 10 ? transferStrToDouble(strs[10]) : null;
		lteCellCfgInfo.antenna_high = strs.length > 11 ? transferStrToDouble(strs[11]) : null;
		lteCellCfgInfo.location_type = strs.length > 12 ? strs[12] : null;
		lteCellCfgInfo.dl_ear_fcn = strs.length > 13 ? transferStrToDouble(strs[13]) : null;
		lteCellCfgInfo.coverage_area = strs.length > 14 ? strs[14] : null;
		currentLoadNeCellW.put(strs[0], lteCellCfgInfo);
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
	 * 获取Cell网元信息
	 * 
	 * @param rncId
	 * @param cellId
	 * @return Cell网元信息
	 */
	public static LteCellCfgInfo findNeCellByVendorEnbCell(String vendor, String enbId, String cellId) {
		String findKey = new StringBuilder().append(vendor).append("-").append(enbId).append("-").append(cellId).toString();
		return findNeCellByVendorEnbCell(findKey);
	}
	
	/**
	 * 获取Cell网元信息
	 * 
	 * @param findKey 根据［vendor］-［enbId］-［cellId］来查找小区信息
	 */
	public static LteCellCfgInfo findNeCellByVendorEnbCell(String findKey) {
		//让其延时加载
		if (cellCfgInfoMap == null) {
			synchronized(executeThread) {
				if (cellCfgInfoMap == null) {
					LteCellCfgCache.load();
					
					// 避免没有邻区时，重复线程启动代码执行
					if (cellCfgInfoMap == null) {
						cellCfgInfoMap = new  HashMap<String, LteCellCfgInfo>();
					}
					
					// 启动邻区刷新服务 
					if (executeThread != null) {
						executeThread.start();
					}
				}
			}
		}
		
		if (cellCfgInfoMap == null || lastLoadNum == 0)
			return null;
		return cellCfgInfoMap.get(findKey);
	}
	
	public static int getNeCellCount() {
		if (cellCfgInfoMap != null) {
			return cellCfgInfoMap.size();
		}
		
		return 0;
	}

	public static boolean isEmpty() {
		return cellCfgInfoMap.isEmpty();
	}

}
