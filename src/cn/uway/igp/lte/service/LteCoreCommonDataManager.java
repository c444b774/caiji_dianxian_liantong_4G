package cn.uway.igp.lte.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.context.AppContext;
import cn.uway.util.FileUtil;
import cn.uway.util.OperatorFileSerial;
import cn.uway.util.OperatorFileSerial.EOPERATOR_FILE_MODE;
import cn.uway.util.TimeUtil;

public class LteCoreCommonDataManager implements Runnable {
	public static class MatchParam {

		protected LteCoreCommonDataEntry entry;

		protected Long timeDiff;

		protected boolean bMatchFromTmsi;
		
		protected String matchFaildCause;

		public MatchParam() {
			entry = null;
			timeDiff = null;
			bMatchFromTmsi = false;
		}
	}

	public static class CacheFileFilter implements FilenameFilter {

		@Override
		public boolean accept(File fileDir, String fileName) {
			if (!fileName.endsWith(SUFFIX_CACHE_FILE)) {
				return false;
			} else {
				String[] fileTag = fileName.split("\\.");
				if (fileTag.length < 3)
					return false;
				return true;
			}
		}
	}

	private static Logger LOGGER = LoggerFactory.getLogger(LteCoreCommonDataManager.class);

	/**
	 * spring注入变量, 缓存文件路径
	 */
	private String cacheFileDir;

	/**
	 * spring注入变量(timeWinOfMinute单位：分钟), 文件时间窗口(如果数据时间和文件时间相差N分钟，则丢弃，不收集)
	 */
	private long timeWinOfMinute;

	private long timeWinOfMillSec;

	/**
	 * spring注入变量(validOfHour单位:小时), 缓存文件有效保存时间，超过该时间的删除
	 */
	private long validOfHour;

	private long validOfMillSec;

	/**
	 * spring注入变量（单位小时)，内存中驻留的数据块存放时间范围
	 */
	private long blockInMemoryHour;

	/**
	 * 每个缓存文件单位时间
	 */
	// private final static long CACHE_FILE_UNIT_TIME = (10 * 60 * 1000L);
	private final static long CACHE_FILE_UNIT_TIME = (5 * 60 * 1000L);

	/**
	 * (在多少时间范围内关联)
	 */
	private final static long CACHE_FILE_TIME_RANGE = (10 * 60 * 1000L);

	private final static long SCAN_TIME_DELAY_MILLSEC = CACHE_FILE_TIME_RANGE / 2;

	/**
	 * 文件操作锁
	 */
	private static final Object fileWriteLock = new Object();

	private static final String SUFFIX_PART_FILE = ".cache.part";

	private static final String SUFFIX_CACHE_FILE = ".cache";

	private static final String SUFFIX_CACHE_TEMP_FILE = ".cache.tmp";

	private static Map<Long, Long> s_prevTaskScanFileTime = new HashMap<Long, Long>();

	/**
	 * key=recordTime / CACHE_FILE_UNIT_TIME 每个时间点的文件读写handle map
	 */
	private Map<Long, OperatorFileSerial> fsTimeWriteMap = new HashMap<Long, OperatorFileSerial>();

	/**
	 * 每个时间点文件缓存，在文件中已排序好 <cacheFileTime, <[tmsi(高32位)+enbUes1apId(低32位)]Key, LteCoreCommonDataEntry list>>
	 */
	// private ConcurrentHashMap<Long, Map<Long, LteCoreCommonDataEntry>> fileTimeReadMap = null;
	private volatile Map<Long, Map<Long, LteCoreCommonDataEntry>> fileTimeReadMap = null;

	/**
	 * 用于实时读取功能；读取文件时间窗口(timeWinOfMinute)内的数据</br> 当有新的数据到达时，将移出最早时间的数据到fileTimeReadMap中； * 和fileTimeReadyCacheFileMap配套使用 <cacheFileTime,
	 * <[tmsi(高32位)+enbUes1apId(低32位)]Key, LteCoreCommonDataEntry list>>
	 */
	private Map<Long, Map<Long, LteCoreCommonDataEntry>> fileTimePrepareReadMap = new TreeMap<>();

	/**
	 * 用于实时读取功能；记录文件时间窗口(timeWinOfMinute)内对应的文件名</br> 和fileTimeReadyReadMap配套使用 <cacheFileTime, Set<已经加载过的文件名>>
	 */
	private Map<Long, Set<String>> fileTimePrepareCacheFileMap = new HashMap<>();

	/**
	 * 缓存中存在的最小和最大数据时间
	 */
	protected volatile Long minTimeFileInCache;

	protected volatile Long maxTimeFileInCache;

	private volatile boolean isCacheLoadReady;

	/**
	 * 分布式服务器数量和索引
	 */
	private int distributeServerNUmber;

	private int distributeIndex;

	/**
	 * 是否被初始化
	 */
	private boolean bInit = false;

	/**
	 * 每个时间单位最大的记录数
	 */
	// private int maxRecordInUnitTimeFile = 512*10000;
	private int maxRecordInUnitTimeFile = 256 * 10000;

	public static final int INVALID_RECORD_PARAM = -1;

	public static final int INVALID_RECORD_TIME = -2;

	/** 最大保留在内存中的缓存个数 */
	private int nMaxBlock;

	/**
	 * 组装文件名
	 * 
	 * @cacheFileDir 缓存目录
	 * @param taskID
	 *            任务id
	 * @param nRecordTime
	 *            记录时间
	 * @parm groupIndex 数据分组index
	 * @param rawFileTime
	 *            文件时间(只在type==0时使用)
	 * @param type
	 *            文件类型
	 * 
	 *            [任务ID].[enb group id].[原始文件时间].cache.part
	 * @return
	 */
	protected static String makeCachePartFileName(String cacheFileDir, long taskID, long nRecordTime, Date rawFileTime) {
		StringBuilder sb = new StringBuilder();

		nRecordTime -= (nRecordTime % CACHE_FILE_UNIT_TIME);
		String timeStamp = TimeUtil.getDateString_yyyyMMddHHmm(new Date(nRecordTime));

		sb.append(cacheFileDir);
		sb.append(timeStamp).append(File.separator);

		// 检测存储目录是否存在，如果不存在，则创建
		File fileDir = new File(sb.toString());
		synchronized (fileWriteLock) {
			if (!fileDir.exists() && !fileDir.isDirectory()) {
				fileDir.mkdir();
			}
		}

		sb.append(taskID);
		// sb.append(".").append(组装文件名timeStamp);
		// sb.append(".").append(groupIndex);
		sb.append(".").append(TimeUtil.getDateString_yyyyMMddHHmmss(rawFileTime)).append(SUFFIX_PART_FILE);

		return sb.toString();
	}

	/**
	 * [任务ID].[enb group id].cache.tmp
	 * 
	 * @param partFileName
	 *            以一个原始核心网文件为单位，生产出来的缓存文件片
	 * @return
	 */
	protected static String makeCacheTempFileName(String partFileName) {
		if (partFileName == null)
			return null;

		int pos = partFileName.lastIndexOf(SUFFIX_PART_FILE);
		if (pos < 0)
			return null;

		String cacheTempFileName = partFileName.substring(0, pos);
		// pos = cacheTempFileName.lastIndexOf('.');
		// if (pos < 0)
		// return null;
		//
		// cacheTempFileName = cacheTempFileName.substring(0, pos);
		// by tyler
		// return cacheTempFileName + SUFFIX_CACHE_TEMP_FILE;
		return cacheTempFileName + SUFFIX_CACHE_FILE;
	}

	/**
	 * [任务ID].[enb group id].cache
	 * 
	 * @param cacheTempFileName
	 *            正在生成的临时缓存文件
	 * @return
	 */
	protected static String makeCacheFileName(String cacheTempFileName) {
		if (cacheTempFileName == null)
			return null;

		int pos = cacheTempFileName.lastIndexOf(SUFFIX_PART_FILE);
		if (pos < 0) {
			pos = cacheTempFileName.lastIndexOf(SUFFIX_CACHE_TEMP_FILE);
		}
		
		if (pos < 0)
			return null;
		
		return cacheTempFileName.substring(0, pos) + SUFFIX_CACHE_FILE;
	}

	/**
	 * 从缓存文件全名中，获得缓存文件名
	 * 
	 * @param cacheFileFullName
	 * @return
	 */
	protected static String getCacheFileTime(String cacheFileFullName) {
		String[] dirnames = cacheFileFullName.split("\\" + File.separator);
		if (dirnames.length > 1) {
			return dirnames[dirnames.length - 1];
		}

		return null;
	}

	/**
	 * 是否是可使用状态，如果config.ini中的 system.lte.coreCommonData.cacheFileDir=none, 则为无效状态，不使用关联逻辑
	 * 
	 * @return
	 */
	public boolean isEnableState() {
		return bInit;
	}

	public synchronized void init() {
		if (bInit)
			return;

		if (this.timeWinOfMinute < 1)
			this.timeWinOfMinute = 120;

		if (this.validOfHour < 1)
			this.validOfHour = 24;

		if (this.blockInMemoryHour < 1)
			this.blockInMemoryHour = 1;

		this.timeWinOfMillSec = timeWinOfMinute * (60 * 1000L);
		this.validOfMillSec = validOfHour * (60 * 60 * 1000L);

		LOGGER.debug("LteCoreCommonDataManager.init() timeWinOfMinute={} validOfHour={} blockInMemoryHour={} cacheFileDir={}", new Object[]{
				timeWinOfMinute, validOfHour, blockInMemoryHour, cacheFileDir});

		if (cacheFileDir != null && cacheFileDir.equalsIgnoreCase("none")) {
			bInit = false;
			return;
		}

		if (cacheFileDir == null || cacheFileDir.trim().length() < 1 || cacheFileDir.indexOf("$") >= 0) {
			cacheFileDir = "./cache/lteCoreCommonData";
		}

		if (cacheFileDir != null) {
			if (!cacheFileDir.endsWith(File.separator)) {
				cacheFileDir += File.separator;
			}
		}

		// 初始化缓存目录
		if (cacheFileDir != null && cacheFileDir.trim().length() > 0) {
			File fdir = new File(cacheFileDir);
			if (!fdir.exists() || !fdir.isDirectory()) {
				synchronized (fileWriteLock) {
					fdir.mkdirs();
				}
			}
		}

		nMaxBlock = (int) ((blockInMemoryHour * 60 * 60 * 1000l) / (CACHE_FILE_UNIT_TIME));
		fileTimeReadMap = new ConcurrentHashMap<Long, Map<Long, LteCoreCommonDataEntry>>(nMaxBlock);

		bInit = true;
	}

	/**
	 * 添加公共数据 Long类型的参数很多是无符号的int,所以用long表示
	 * 
	 * @param rawFileTime
	 *            原始文件时间
	 * @param recordTime
	 *            记录时间
	 * @param key
	 *            主键
	 * @param values
	 *            值数组
	 * @param taskID
	 *            任务id
	 * @throws Exception
	 */
	public int addCoreCommonData(Date rawFileTime, long recordTime, Long mmeUeS1apID, Long nGutiMTMSI, Integer nGutiMMEGI, Integer nGutiMMEC,
			Long imsi, String msisdn, long taskID) throws Exception {

		if (!bInit)
			return INVALID_RECORD_PARAM;

		// 对无效的数据丢弃
		if (nGutiMMEGI == null || nGutiMMEGI == 0xFFFFL || nGutiMMEC == null || nGutiMMEC == 0xFFFFL || imsi == null || imsi < 100000000000000L)
			return INVALID_RECORD_PARAM;

		// 对如果两个查询主键都无效的数据丢弃
		if ((mmeUeS1apID == null || mmeUeS1apID == 0xFFFFFFFFL) && (nGutiMTMSI == null || nGutiMTMSI == 0xFFFFFFFFL))
			return INVALID_RECORD_PARAM;

		// 如果数据时间和文件时间相差N个小时，则丢弃
		if (Math.abs(recordTime - rawFileTime.getTime()) > timeWinOfMillSec)
			return INVALID_RECORD_TIME;

		int recordTimeIndex = (int) (recordTime / CACHE_FILE_UNIT_TIME);

		Long fileIndexKey = (recordTimeIndex & 0xFFFFFFFFL);
		OperatorFileSerial fs = fsTimeWriteMap.get(fileIndexKey);
		if (fs == null) {
			// String cachePartFile = makeCachePartFileName(this.cacheFileDir, taskID,
			// recordTime, 0l,
			// rawFileTime);
			String cachePartFile = makeCachePartFileName(this.cacheFileDir, taskID, recordTime, rawFileTime);

			File file = new File(cachePartFile);
			boolean bDelExistFile = false;
			synchronized (fileWriteLock) {
				if (file.exists())
					bDelExistFile = file.delete();
			}

			if (bDelExistFile) {
				LOGGER.debug("删除已存在的partfile:{}", cachePartFile);
			}

			fs = new OperatorFileSerial(EOPERATOR_FILE_MODE.e_Write, cachePartFile);
			fsTimeWriteMap.put(fileIndexKey, fs);
		}

		// Long key = makeHWCoreKey(enbID, enbUeS1apID);
		// fs.write(key);
		// 每条记录40个字节
		fs.write((int) (recordTime % CACHE_FILE_UNIT_TIME));
		fs.write((int) (mmeUeS1apID == null ? 0xFFFFFFFF : mmeUeS1apID));
		fs.write((int) (nGutiMTMSI == null ? 0xFFFFFFFF : nGutiMTMSI));
		fs.write((short) (nGutiMMEC == null ? 0xFFFF : nGutiMMEC));
		fs.write((short) (nGutiMMEGI == null ? 0xFFFF : nGutiMMEGI));
		fs.write((long) (imsi == null ? 0xFFFFFFFFFFFFFFFFL : imsi));
		fs.write_fixedString(msisdn, 16);

		return 0;
	}

	/**
	 * 结束写缓存文件
	 * 
	 * @param taskID
	 * @param rawFileTime
	 */
	public void endWrite(long taskID, Date rawFileTime) {
		if (!bInit)
			return;

		Iterator<Entry<Long, OperatorFileSerial>> iter = fsTimeWriteMap.entrySet().iterator();
		while (iter.hasNext()) {
			Entry<Long, OperatorFileSerial> entry = iter.next();
			// long fileIndexKey = entry.getKey();
			OperatorFileSerial fs = entry.getValue();
			if (fs == null)
				continue;

			try {
				fs.flush();
				fs.close();
				
				// 直接将partfile改为正式文件名
				String cachePartFileName = fs.getOperatorFileName();
				String cacheFile = makeCacheFileName(cachePartFileName);
				// 将临时文件更改成正式的cache文件
				File fileSrc = new File(cachePartFileName);
				File fileDst = new File(cacheFile);

				if (fileDst.exists() && fileDst.isFile()) {
					// 将已存在的文件备份
					String timeTmpBakFile = cacheFile + ".bak";
					File fileBak = new File(timeTmpBakFile);
					if (fileBak.exists() && fileBak.isFile()) {
						fileBak.delete();
					}

					// 将临时缓存文件备份
					fileCopy(fileDst, fileBak);

					// 合并当前的片断文件到备份文件中
					fileMerge(fileSrc, fileBak);
					fileSrc.delete();

					// 将备份文件更改成临时缓存文件;
					synchronized (fileWriteLock) {
						fileDst.delete();
						fileBak.renameTo(fileDst);
					}
				} else {
					// 将片断文件更名成临时缓存文件
					fileSrc.renameTo(fileDst);
				}

			} catch (IOException e) {
				LOGGER.error("关闭文件发生异常", e);
			}
		}

		fsTimeWriteMap.clear();
		complateCacheFile(taskID, rawFileTime);
	}

	/**
	 * 缓存文件完成处理．
	 * 
	 * @param taskID
	 *            任务id
	 * @param rawFileTime
	 *            原始文件时间
	 */
	protected void complateCacheFile(final Long taskID, Date rawFileTime) {
		Long currTime = System.currentTimeMillis();
		synchronized (s_prevTaskScanFileTime) {
			Long nPrevScanFileTime = s_prevTaskScanFileTime.get(taskID);
			if (nPrevScanFileTime == null) {
				s_prevTaskScanFileTime.put(taskID, currTime);
				nPrevScanFileTime = currTime;
			}

			if ((currTime - nPrevScanFileTime) < SCAN_TIME_DELAY_MILLSEC)
				return;

			s_prevTaskScanFileTime.put(taskID, currTime);
		}

		long nRawFileTime = rawFileTime.getTime();
		final long gTimeInvalidLeft = nRawFileTime - validOfMillSec;
		// 扫描cache下面的目录
		File cacheDirFile = new File(cacheFileDir);
		synchronized (fileWriteLock) {
			cacheDirFile.list(new FilenameFilter() {

				@Override
				public boolean accept(File fileParentDIr, String fileName) {
					if (fileName.length() != 12)
						return false;

					long cacheFileTime = 0;
					try {
						cacheFileTime = TimeUtil.getyyyyMMddHHmmDate(fileName).getTime();
					} catch (ParseException e) {
						return false;
					}

					String timeDirName = fileParentDIr.getAbsolutePath() + File.separator + fileName;

					// 删除掉过期的文件
					if (cacheFileTime <= gTimeInvalidLeft) {
						removeDir(timeDirName);
						return false;
					}

					return false;
				}
			});
		}
	}

	/**
	 * 复制文件
	 * 
	 * @param src
	 * @param dst
	 */
	private void fileCopy(File src, File dst) throws IOException {
		FileInputStream fi = null;
		FileOutputStream fo = null;
		FileChannel in = null;
		FileChannel out = null;
		try {
			fi = new FileInputStream(src);
			fo = new FileOutputStream(dst);
			in = fi.getChannel();// 得到对应的文件通道
			out = fo.getChannel();// 得到对应的文件通道
			in.transferTo(0, in.size(), out);// 连接两个通道，并且从in通道读取，然后写入out通道
		} catch (IOException e) {
			throw e;
		} finally {
			try {
				fi.close();
				in.close();
				fo.close();
				out.close();
			} catch (IOException e) {
				throw e;
			}
		}
	}

	/**
	 * 合并文件
	 * 
	 * @param src
	 * @param dst
	 */
	private void fileMerge(File src, File dst) throws IOException {
		FileInputStream fi = null;
		FileOutputStream fo = null;
		FileChannel in = null;
		FileChannel out = null;
		try {
			fi = new FileInputStream(src);
			fo = new FileOutputStream(dst, true);
			in = fi.getChannel();// 得到对应的文件通道
			out = fo.getChannel();// 得到对应的文件通道
			in.transferTo(0, in.size(), out);// 连接两个通道，并且从in通道读取，然后写入out通道
		} catch (IOException e) {
			throw e;
		} finally {
			try {
				fi.close();
				in.close();
				fo.close();
				out.close();
			} catch (IOException e) {
				throw e;
			}
		}
	}

	/**
	 * 删除目录（文件夹）以及目录下的文件
	 * 
	 * @param sPath
	 *            被删除目录的文件路径
	 * @return 目录删除成功返回true，否则返回false
	 */
	private void removeDir(String sPath) {
		// 如果sPath不以文件分隔符结尾，自动添加文件分隔符
		if (!sPath.endsWith(File.separator)) {
			sPath = sPath + File.separator;
		}
		File fileDir = new File(sPath);
		// 如果dir对应的文件不存在，或者不是一个目录，则退出
		if (!fileDir.exists() || !fileDir.isDirectory()) {
			return;
		}

		// 删除文件夹下的所有文件(包括子目录)
		File[] files = fileDir.listFiles();
		for (int i = 0; i < files.length; i++) {
			// 删除子文件
			if (files[i].isFile()) {
				FileUtil.removeFile(files[i].getAbsolutePath());
			} // 删除子目录
			else {
				removeDir(files[i].getAbsolutePath());
			}
		}

		// 删除当前目录
		fileDir.delete();
	}

	public MatchParam matchLteCoreCommonDataEntry(long cdrTime, Long mmeUeS1apID, Long tmsi, Integer mmegi, Integer mmec) {
		MatchParam matchResult = new MatchParam();
		if (fileTimeReadMap == null) {
			matchResult.matchFaildCause = "fileTimeReadMap is not ready.";
			return matchResult;
		}

		// 根据cdrTime, 推测出自己需要获取的公共信息缓存文件应该在哪几个文件中
		long startTime = cdrTime - CACHE_FILE_TIME_RANGE;
		long endTime = (cdrTime + CACHE_FILE_TIME_RANGE);
		long fileStartTime = startTime - (startTime % CACHE_FILE_UNIT_TIME);
		long fileEndTime = endTime - (endTime % CACHE_FILE_UNIT_TIME);
		if ((fileEndTime + CACHE_FILE_UNIT_TIME) < endTime) {
			fileEndTime += CACHE_FILE_UNIT_TIME;
		}

		int timeCount = (int) ((fileEndTime - fileStartTime) / CACHE_FILE_UNIT_TIME) + 1;
		int timeMatchedCachePart = 0;
		for (int i = 0; i < timeCount; ++i) {
			// 文件时间
			Long fileTime = fileStartTime + i * CACHE_FILE_UNIT_TIME;
			// Date d = new Date(fileTime);
			Map<Long, LteCoreCommonDataEntry> partTimeCacheMap = fileTimeReadMap.get(fileTime);

			// 如果在内存中的缓存为空，则从文件中读取缓存
			if (partTimeCacheMap == null)
				continue;
			
			++timeMatchedCachePart;
			// 优先使用tmsi关联
			if (tmsi != null && tmsi != 0xFFFFFFFFL) {
				if (matchEntryByTMSI(partTimeCacheMap, tmsi, mmec, mmegi, fileTime, cdrTime, startTime, endTime, matchResult)) {
					continue;
				}
			}

			if (matchResult.entry == null && mmeUeS1apID != null && mmeUeS1apID != 0xFFFFFFFFL) {
				if (matchEntryByUES1apID(partTimeCacheMap, mmeUeS1apID, mmec, mmegi, fileTime, cdrTime, startTime, endTime, matchResult)) {
					continue;
				}
			}
		}
		
		if (timeMatchedCachePart < 1) {
			matchResult.matchFaildCause = "unmatched all cache part of time.";
		} else {
			matchResult.matchFaildCause = "unmatched key.";
		}

		return matchResult;
	}

	/**
	 * tmsi配匹cdr time之前最近的一条记录
	 * 
	 * @param partTimeCacheMap
	 *            时间点缓存块
	 * @param tmsi
	 *            tmsi或mmeS1apID key值
	 * @param mmec
	 * @param mmegi
	 * @param fileTime
	 *            文件时间（用作偏移值，计算出时间entry的时间值)
	 * @param cdrTime
	 *            cdr记录时间
	 * @param startTime
	 *            开始配匹时间
	 * @param endTime
	 *            结束配匹时间
	 * @param compareParam
	 *            配匹参数
	 * @return
	 */
	private boolean matchEntryByTMSI(Map<Long, LteCoreCommonDataEntry> partTimeCacheMap, Long tmsi, Integer mmec, Integer mmegi, long fileTime,
			long cdrTime, long startTime, long endTime, MatchParam compareParam) {

		long key = (tmsi & 0xFFFFFFFFL);
		LteCoreCommonDataEntry entry = partTimeCacheMap.get(key);
		if (entry == null)
			return false;

		// 关联
		boolean bMatched = false;
		while (entry != null) {
			long entryTime = entry.time + fileTime;

			// tmsi时间不需要绝对化到cdr时间前N分钟内
			/*
			 * if (entryTime < startTime) { entry = entry.getNextTMSIEntry(); continue; } else
			 */
			if (entryTime > cdrTime || entryTime > endTime) {
				break;
			}

			if ((mmec != null && mmec.equals(entry.getMMEC())) && (mmegi != null && mmegi.equals(entry.getMMEGI()))) {
				// 因为match函数传进来的map都是从小至大排序好的，所以最近一个map和list的最近一个entry时间一定比前一个是新的
				compareParam.entry = entry;
				compareParam.bMatchFromTmsi = true;
				bMatched = true;
			}

			entry = entry.getNextTMSIEntry();
		}

		return bMatched;
	}

	/**
	 * 配匹时间cdr_time前后最近的一条记录
	 * 
	 * @param partTimeCacheMap
	 *            时间点缓存块
	 * @param tmsi
	 *            tmsi或mmeS1apID key值
	 * @param mmec
	 * @param mmegi
	 * @param fileTime
	 *            文件时间（用作偏移值，计算出时间entry的时间值)
	 * @param cdrTime
	 *            cdr记录时间
	 * @param startTime
	 *            开始配匹时间
	 * @param endTime
	 *            结束配匹时间
	 * @param compareParam
	 *            配匹参数
	 * @return
	 */
	private boolean matchEntryByUES1apID(Map<Long, LteCoreCommonDataEntry> partTimeCacheMap, Long ueS1apID, Integer mmec, Integer mmegi,
			Long fileTime, long cdrTime, long startTime, long endTime, MatchParam compareParam) {

		long key = ((ueS1apID & 0xFFFFFFFFL) << 32);
		LteCoreCommonDataEntry entry = partTimeCacheMap.get(key);
		if (entry == null)
			return false;

		// 关联
		boolean bMatched = false;
		while (entry != null) {
			long entryTime = entry.time + fileTime;
			if (entryTime < startTime) {
				entry = entry.getNextUES1apIDEntry();
				continue;
			} else if (entryTime > endTime)
				break;

			if ((mmec != null && mmec.equals(entry.getMMEC())) && (mmegi != null && mmegi.equals(entry.getMMEGI()))) {

				bMatched = true;
				Long currEntryTimeDiff = Math.abs(entryTime - cdrTime);
				if (compareParam.timeDiff == null || currEntryTimeDiff <= compareParam.timeDiff) {
					compareParam.entry = entry;
					compareParam.timeDiff = currEntryTimeDiff;
				} else {
					// 因为时间是排序的，时间差如果越来越大，后面的就不需要再比较了
					break;
				}
			}

			entry = entry.getNextUES1apIDEntry();
		}

		return bMatched;
	}

	/**
	 * 获得最大的缓存数据时间
	 * 
	 * @return
	 */
	public boolean getMinMaxCacheDateTime(Date minCacheFileTime, Date maxCacheFileTime) {
		if (!bInit)
			return false;

		String minDirName = null;
		String maxDirName = null;
		File cacheDirFile = new File(cacheFileDir);
		String[] dirnames = cacheDirFile.list();
		for (String dirname : dirnames) {
			if (dirname.length() != 12)
				continue;

			if (minDirName == null) {
				minDirName = dirname;
			}

			if (maxDirName == null) {
				maxDirName = dirname;
			}

			if (minDirName.compareTo(dirname) > 0)
				minDirName = dirname;

			if (maxDirName.compareTo(dirname) < 0)
				maxDirName = dirname;
		}

		// 此处minDirName、maxDirName任意一个为空结果都一样
		if (minDirName == null)
			return false;

		try {
			if (minCacheFileTime != null)
				minCacheFileTime.setTime(TimeUtil.getyyyyMMddHHmmDate(minDirName).getTime());

			if (maxCacheFileTime != null)
				maxCacheFileTime.setTime(TimeUtil.getyyyyMMddHHmmDate(maxDirName).getTime());

			return true;
		} catch (ParseException e) {
		}

		return false;
	}

	public boolean isCacheReady(Date cdrFileTime) {
		if (this.maxTimeFileInCache == null)
			return false;

		if ((maxTimeFileInCache + CACHE_FILE_UNIT_TIME)  < cdrFileTime.getTime()) {
			return false;
		}

		return true;
	}

	/**
	 * 判断一下，cache正在加载时的查询
	 * 
	 * @param fileTime
	 *            文件时间
	 * @return
	 */
	public boolean isCacheLoadedReady(long fileTime) {
		if (this.isCacheLoadReady)
			return true;
		else if (this.minTimeFileInCache != null && this.minTimeFileInCache != 0 && this.maxTimeFileInCache != null
				&& this.minTimeFileInCache < fileTime && this.maxTimeFileInCache > fileTime) {
			return true;
		}

		return false;
	}

	/**
	 * 直接加载已经稳定的数据到主缓存fileTimeReadMap中，通常为第一次加载；</br> 稳定的数据:时间窗口之前的数据,因为时间窗口可以人为设定，所以认为此部份数据已经稳定了.
	 */
	public void loadMainCache() {
		Date minCacheFileTime = new Date();
		Date maxCacheFileTime = new Date();
		int nLoadBlock = 0;
		if (!getMinMaxCacheDateTime(minCacheFileTime, maxCacheFileTime))
			return;
		try {
			// 内存中的缓存从最近的到最新的，逐个加载
			long fileTime = maxCacheFileTime.getTime() - timeWinOfMillSec;
			LOGGER.debug("第一次开始扫描缓存文件并加载到内存. 从时间点：{}开始向前尝试加载. 总共可以加载内存块个数:{}", new Object[]{TimeUtil.getDateString_yyyyMMddHHmm(new Date(fileTime)),
					nMaxBlock});

			while (fileTime >= minCacheFileTime.getTime() && (fileTimeReadMap.size() < nMaxBlock)) {
				// 找出当前时间点的数据文件，加载到内存
				String[] cacheFiles = findCacheFile(fileTime);
				if (cacheFiles == null || cacheFiles.length < 1) {
					fileTime -= CACHE_FILE_UNIT_TIME;
					continue;
				}

				// 按每5分钟大约256万条数据计算，初始化map
				Map<Long, LteCoreCommonDataEntry> partTimeCacheMap = new HashMap<Long, LteCoreCommonDataEntry>(maxRecordInUnitTimeFile);
				long entryNumber = 0;
				for (String cacheFile : cacheFiles) {
					long nReadNumber = readHWCdtCacheFile(partTimeCacheMap, cacheFile);
					if (nReadNumber < 1)
						continue;

					entryNumber += nReadNumber;
				}

				if (partTimeCacheMap.size() > maxRecordInUnitTimeFile) {
					maxRecordInUnitTimeFile = (int) (partTimeCacheMap.size() * 1.1);
				}

				if (maxTimeFileInCache == null || maxTimeFileInCache < fileTime)
					maxTimeFileInCache = fileTime;

				if (minTimeFileInCache == null || minTimeFileInCache > fileTime)
					minTimeFileInCache = fileTime;

				// 如果达到最大block数，那么就不继续加载比当前fileTime更老的数据；
				if (fileTimeReadMap.size() >= nMaxBlock) {
					this.minTimeFileInCache = fileTime;
				}

				partTimeCacheMap = fileTimeReadMap.put(fileTime, partTimeCacheMap);
				++nLoadBlock;

				LOGGER.debug(
						"第一次加载fileTime:[{}]缓存块文件到内存成功，当前时间点子文件个数:{}, 当前时间点记录数：{}， 缓存块总数:{}/{}, 缓存时间范围:[{}]-[{}]",
						new Object[]{TimeUtil.getDateString_yyyyMMddHHmm(new Date(fileTime)), cacheFiles.length, entryNumber, fileTimeReadMap.size(),
								nMaxBlock,
								TimeUtil.getDateString_yyyyMMddHHmm(new Date(this.minTimeFileInCache == null ? 0 : this.minTimeFileInCache)),
								TimeUtil.getDateString_yyyyMMddHHmm(new Date(this.maxTimeFileInCache == null ? 0 : this.maxTimeFileInCache))});
				fileTime -= CACHE_FILE_UNIT_TIME;
			}
			LOGGER.debug(
					"第一次缓存加载完成，本次共加载{}个内存块， 缓存块总数:{}/{}, 缓存时间范围:[{}]-[{}]",
					new Object[]{nLoadBlock, fileTimeReadMap.size(), nMaxBlock,
							TimeUtil.getDateString_yyyyMMddHHmm(new Date(this.minTimeFileInCache == null ? 0 : this.minTimeFileInCache)),
							TimeUtil.getDateString_yyyyMMddHHmm(new Date(this.maxTimeFileInCache == null ? 0 : this.maxTimeFileInCache))});
			if (maxTimeFileInCache != null)
				isCacheLoadReady = true;
		} catch (Exception e) {
			LOGGER.error("加载缓存出错", e);
		}
	}

	/**
	 * 每次增量扫描时间窗口内的数据到fileTimeReadyReadMap缓存中;</br> 如果数据已经稳定(超出时间窗口范围内的数据)，则移除到主缓存中.
	 */
	public void loadPrepareCache() {
		Date minCacheFileTime = new Date();
		Date maxCacheFileTime = new Date();
		int nReleaseBlock = 0;
		int nLoadBlock = 0;
		if (!getMinMaxCacheDateTime(minCacheFileTime, maxCacheFileTime))
			return;

		// 最大保留在fileTimeReadyReadMap中的缓存个数
		int nMaxPrepareBlock = (int) (timeWinOfMillSec / CACHE_FILE_UNIT_TIME) + (timeWinOfMillSec % CACHE_FILE_UNIT_TIME == 0 ? 0 : 1);

		// 内存中的缓存从最近的到最新的，逐个加载
		long fileTime = 0;
		if (fileTimePrepareReadMap.size() != 0) {
			fileTime = new ArrayList<>(fileTimePrepareReadMap.keySet()).get(0);
		} else if (maxTimeFileInCache != null) {
			// 如果此处没有数据，那么程序会不停的向前找数据
			fileTime = maxTimeFileInCache + CACHE_FILE_UNIT_TIME;
		} else {
			fileTime = minCacheFileTime.getTime();
		}
		LOGGER.debug("开始扫描增量缓存文件并加载到内存. 从时间点：{}开始向后尝试加载. 加载前缓存中的内存块个数:{}/{}", new Object[]{TimeUtil.getDateString_yyyyMMddHHmm(new Date(fileTime)),
				fileTimePrepareReadMap.size(), nMaxPrepareBlock});
		
		// pendingMap在后面增量更新时，设成普通map不可以，避免读写线程竟争
		Map<Long, Map<Long, LteCoreCommonDataEntry>>  pendingMap = null;
		Long pendingMinTimeFileInCache = this.minTimeFileInCache;
		Long pendingMaxTimeFileInCache = this.maxTimeFileInCache;
		// 每次最多加载5个新的内存块，以防瞬间采集中断恢复后，内存数据暴增而导致虚拟机崩溃．
		while (fileTime <= maxCacheFileTime.getTime() && nLoadBlock < 5) {
			// 找出当前时间点的数据文件，加载到内存
			String[] cacheFiles = findCacheFile(fileTime);
			if (cacheFiles == null || cacheFiles.length <= 0) {
				fileTime += CACHE_FILE_UNIT_TIME;
				continue;
			}
			long entryNumber = loadCacheDataInReadMap(fileTimePrepareReadMap, cacheFiles, fileTime);
			// 如果达到指定大小，那么就将fileTimeReadyReadMap中最老的数据移到fileTimeReadMap中
			if (fileTimePrepareReadMap.size() > nMaxPrepareBlock) {
				long complateTime = new ArrayList<>(fileTimePrepareReadMap.keySet()).get(0);
				Map<Long, LteCoreCommonDataEntry> completeTimeCacheMap = fileTimePrepareReadMap.remove(complateTime);
				fileTimePrepareCacheFileMap.remove(complateTime);
				if (completeTimeCacheMap != null) {
					if (pendingMap == null) {
						pendingMap = new HashMap<Long, Map<Long, LteCoreCommonDataEntry>>(nMaxBlock+1);
						pendingMap.putAll(fileTimeReadMap);
					}
					
					//将已收集好的内存块放到pendingMap中
					pendingMap.put(complateTime, completeTimeCacheMap);
					// 删除过期的内存块
					if (pendingMap.size() > nMaxBlock) {
						assert (pendingMinTimeFileInCache != null);
						boolean bRemove = (pendingMap.remove(pendingMinTimeFileInCache) != null);
						long nextMinTimeFileInCache = pendingMinTimeFileInCache;
						// 重设缓存最小数据时间
						while (nextMinTimeFileInCache < pendingMaxTimeFileInCache) {
							nextMinTimeFileInCache += CACHE_FILE_UNIT_TIME;
							if (pendingMap.containsKey(nextMinTimeFileInCache))
								break;
						}
						pendingMinTimeFileInCache = nextMinTimeFileInCache;
						
						++nLoadBlock;
						if (bRemove)
							++nReleaseBlock;
						
						LOGGER.debug(
								"从pending内存中移除fileTime:[{}]的内存块{}，当前缓存块总数:{}, 缓存时间范围:[{}]-[{}]",
								new Object[]{TimeUtil.getDateString_yyyyMMddHHmm(new Date(pendingMinTimeFileInCache)), (bRemove ? "成功" : "失败"),
										pendingMap.size(), TimeUtil.getDateString_yyyyMMddHHmm(new Date(nextMinTimeFileInCache)),
										TimeUtil.getDateString_yyyyMMddHHmm(new Date(pendingMaxTimeFileInCache))});
					}
					
					// 设置缓存中存在的最大数据时间为fileTimePrepareReadMap中删除时间
					pendingMaxTimeFileInCache = complateTime;
					if (pendingMinTimeFileInCache == null) {
						// 如果缓存中的时间为空，初始化为最小的文件时间；
						pendingMinTimeFileInCache = pendingMaxTimeFileInCache;
					}
				}
			}
			
			if (entryNumber > 0) {
				LOGGER.debug(
						"刷新fileTime:[{}]缓存块文件到内存成功，当前时间点子文件个数:{}, 本次新加载到记录数：{}， PrepareCache缓存块总数:{}/{}",
						new Object[]{TimeUtil.getDateString_yyyyMMddHHmm(new Date(fileTime)), cacheFiles.length, entryNumber,
								fileTimePrepareReadMap.size(), nMaxPrepareBlock});
			}
			
			fileTime += CACHE_FILE_UNIT_TIME;
		}
		
		// 将后同修改的map替换掉原来的map，并重设minTimeFileInCache, maxTimeFileInCache;
		if (pendingMap != null) {
			this.minTimeFileInCache = pendingMinTimeFileInCache;
			this.fileTimeReadMap = pendingMap;
			this.maxTimeFileInCache = pendingMaxTimeFileInCache;
			
			if (!isCacheLoadReady)
				isCacheLoadReady = true;
		}
		
		LOGGER.debug(
				"缓存加载完成，本次共加载{}个新的内存块，释放{}个早期的内存块， 缓存块总数:{}/{}, 缓存时间范围:[{}]-[{}]",
				new Object[]{nLoadBlock, nReleaseBlock, fileTimeReadMap.size(), nMaxBlock,
						TimeUtil.getDateString_yyyyMMddHHmm(new Date(this.minTimeFileInCache == null ? 0 : this.minTimeFileInCache)),
						TimeUtil.getDateString_yyyyMMddHHmm(new Date(this.maxTimeFileInCache == null ? 0 : this.maxTimeFileInCache))});

	}

	/**
	 * 加载文件中的数据到可读映射表中
	 * 
	 * @param fileTimePendingReadMap
	 * @param cacheFiles
	 * @param fileTime
	 * @return
	 */
	private long loadCacheDataInReadMap(Map<Long, Map<Long, LteCoreCommonDataEntry>> fileTimePendingReadMap, String[] cacheFiles, long fileTime) {

		Map<Long, LteCoreCommonDataEntry> partTimeCacheMap = null;
		Set<String> cacheFileSet = null;
		/* 初始化map */
		if (fileTimePrepareCacheFileMap.containsKey(fileTime)) {
			cacheFileSet = fileTimePrepareCacheFileMap.get(fileTime);
			partTimeCacheMap = fileTimePendingReadMap.get(fileTime);
		} else {
			cacheFileSet = new HashSet<>();
			fileTimePrepareCacheFileMap.put(fileTime, cacheFileSet);
			// 按每5分钟大约256万条数据计算，初始化map
			partTimeCacheMap = new HashMap<Long, LteCoreCommonDataEntry>(maxRecordInUnitTimeFile);
			fileTimePendingReadMap.put(fileTime, partTimeCacheMap);
		}
		long entryNumber = 0;
		for (String cacheFile : cacheFiles) {
			if (cacheFileSet.contains(cacheFile))
				continue;
			long nReadNumber = readHWCdtCacheFile(partTimeCacheMap, cacheFile);
			cacheFileSet.add(cacheFile);
			if (nReadNumber < 1)
				continue;
			entryNumber += nReadNumber;
		}

		if (partTimeCacheMap.size() > maxRecordInUnitTimeFile) {
			maxRecordInUnitTimeFile = (int) (partTimeCacheMap.size() * 1.1);
		}

		return entryNumber;
	}

	private String[] findCacheFile(final long readCacheFileTime) {
		String timeStamp = TimeUtil.getDateString_yyyyMMddHHmm(new Date(readCacheFileTime));

		String timeCacheFileDir = cacheFileDir + timeStamp + File.separator;
		File cacheDirFile = new File(timeCacheFileDir);
		CacheFileFilter cacheFilefilter = new CacheFileFilter();
		String[] files = cacheDirFile.list(cacheFilefilter);
		// if (cacheFilefilter.isHasPendingFile())
		// return null;

		if (files != null) {
			for (int i = 0; i < files.length; ++i) {
				files[i] = timeCacheFileDir + files[i];
			}
		}

		return files;
	}

	private long readHWCdtCacheFile(Map<Long, LteCoreCommonDataEntry> partTimeCacheMap, String cacheFile) {
		long startTime = System.currentTimeMillis();
		long entrySize = 0;
		OperatorFileSerial fs = null;
		try {
			fs = new OperatorFileSerial(EOPERATOR_FILE_MODE.e_Read, cacheFile);
			LteCoreCommonDataEntry newEntry = new LteCoreCommonDataEntry();
			boolean bUsed = false;
			while (!fs.isEndOfFile()) {
				newEntry.time = fs.read_int();
				long mmeUeS1apID = fs.read_uint();
				long nGutiMTMSI = fs.read_uint();
				newEntry.mmec = fs.read_short();
				newEntry.mmegi = fs.read_short();
				newEntry.imsi = fs.read_long();
				newEntry.msisdn = fs.read_bytes(16);
				
				// mmec,mmegi一般在0-32768
				if (newEntry.mmec < 0 || newEntry.mmegi < 0)
					continue;

				// mmeUeS1apID放高32位, nGutiMTMSI放低32位，以保证用同一个map，但数据不冲突
				if (mmeUeS1apID != 0xFFFFFFFFL) {
					// 只加载自己服务器的缓存
					if ((mmeUeS1apID % distributeServerNUmber) == distributeIndex) {
						long key = (mmeUeS1apID << 32);
						LteCoreCommonDataEntry minTimeEntry = partTimeCacheMap.get(key);
						if (minTimeEntry == null) {
							minTimeEntry = newEntry;
							partTimeCacheMap.put(key, minTimeEntry);
						} else {
							// 这个addEntry是按时间排序的，如果返回true,代表链表顶部发生了变化，需要重新往map中put一下
							if (minTimeEntry.addUes1apIDEntry(mmeUeS1apID, newEntry)) {
								partTimeCacheMap.put(key, newEntry);
							}
						}
						bUsed = true;
					}
				}

				if (nGutiMTMSI != 0xFFFFFFFFL) {
					// 只加载自己服务器的缓存
					if ((nGutiMTMSI % distributeServerNUmber) == distributeIndex) {
						long key = (nGutiMTMSI & 0xFFFFFFFFL);
						LteCoreCommonDataEntry minTimeEntry = partTimeCacheMap.get(key);
						if (minTimeEntry == null) {
							minTimeEntry = newEntry;
							partTimeCacheMap.put(key, minTimeEntry);
						} else {
							// 这个addEntry是按时间排序的，如果返回true,代表链表顶部发生了变化，需要重新往map中put一下
							if (minTimeEntry.addTmsiEntry(nGutiMTMSI, newEntry)) {
								partTimeCacheMap.put(key, newEntry);
							}
						}
						bUsed = true;
					}
				}

				if (bUsed) {
					newEntry = new LteCoreCommonDataEntry();
				}

				++entrySize;
			}
		} catch (Exception e) {
			LOGGER.error("读取缓存文件发生异常. 缓存文件名:{}", cacheFile, e);
			return -1;
		} finally {
			if (fs != null) {
				try {
					fs.close();
				} catch (Exception e) {
				}
			}
		}

		LOGGER.debug("cacheFile:{} recordCount:{} loadTimeElapse:{}", new Object[]{cacheFile, entrySize, (System.currentTimeMillis() - startTime)});
		return entrySize;
	}

	@Override
	public void run() {
		loadMainCache();
		while (true) {
			try {
				this.loadPrepareCache();
				Thread.sleep(1 * 60 * 1000l);
			} catch (Exception e) {
				LOGGER.error("加载缓存发生错误", e);
			}
		}
	}

	/**
	 * 启动缓存加载线程
	 */
	public void startLoadCache(int distributeServerNumber, int distributeIndex) {
		this.distributeServerNUmber = distributeServerNumber;
		this.distributeIndex = distributeIndex;
		Thread thread = new Thread(this, "缓存加载线程");
		thread.start();
	}

	public static LteCoreCommonDataManager getWriteInstance() {
		LteCoreCommonDataManager manager = AppContext.getBean("lteCoreCommonDataWriteConfig", LteCoreCommonDataManager.class);
		return manager;
	}

	public static LteCoreCommonDataManager getReadInstance() {
		LteCoreCommonDataManager manager = AppContext.getBean("lteCoreCommonDataReadConfig", LteCoreCommonDataManager.class);
		return manager;
	}

	public static Long converToLong(String value) {
		if (value == null)
			return null;

		try {
			return Long.parseLong(value);
		} catch (Exception e) {
			return null;
		}
	}

	public static Integer converToInteger(String value) {
		if (value == null)
			return null;

		try {
			return Integer.parseInt(value);
		} catch (Exception e) {
			return null;
		}
	}

	public String getCacheFileDir() {
		return cacheFileDir;
	}

	public void setCacheFileDir(String cacheFileDir) {
		this.cacheFileDir = cacheFileDir;
	}

	public long getTimeWinOfMinute() {
		return timeWinOfMinute;
	}

	public void setTimeWinOfMinute(long timeWinOfMinute) {
		this.timeWinOfMinute = timeWinOfMinute;
	}

	public long getValidOfHour() {
		return validOfHour;
	}

	public void setValidOfHour(long validOfHour) {
		this.validOfHour = validOfHour;
	}

	public long getBlockInMemoryHour() {
		return blockInMemoryHour;
	}

	public void setBlockInMemoryHour(long blockInMemoryHour) {
		this.blockInMemoryHour = blockInMemoryHour;
	}

}
