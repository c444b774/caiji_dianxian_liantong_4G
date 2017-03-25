package cn.uway.igp.lte.parser.cdt;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.ParseException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.util.FileUtil;
import cn.uway.util.OperatorFileSerial;
import cn.uway.util.OperatorFileSerial.EOPERATOR_FILE_MODE;
import cn.uway.util.TimeUtil;

public class CoreCommonDataManager {
	private static Logger LOGGER = LoggerFactory
			.getLogger(CoreCommonDataManager.class);

	/**
	 * 文件时间窗口(如果数据时间和文件时间相差N个小时，则丢弃)
	 */
	private long timeWinOfMinute = 120L;
	private long timeWinOfMillSec = 0;
	
	/**
	 * 缓存文件有效保存时间，超过该时间的删除
	 */
	private long validOfHour = 24L;
	private long validOfMillSec = 0;
	
	private final static Integer DEFAULT_FILE_GROUP_INDEX = 0;

	/**
	 * 每个缓存文件单位时间
	 */
	private final static long CACHE_FILE_UNIT_TIME = (10 * 60 * 1000L);
	
	/**
	 * (在多少时间范围内关联(
	 */
	private final static long CACHE_FILE_TIME_RANGE = (5 * 60 * 1000L);
	
	/**
	 * 每个enb_id最多缓存多少时间的内存在内存中
	 */
	private final static int MAX_CACHE_BLOCK_IN_MEMORY = 6; 	
	
	/**
	 * 文件操作锁
	 */
	private static final Object fileWriteLock = new Object();
	
	/**
	 * 缓存读取锁
	 */
	private static final Object cacheReadLock = new Object();

	/**
	 * 缓存文件路径
	 */
	private String cacheFileDir;
	
	private static final String SUFFIX_PART_FILE = ".cache.part";
	private static final String SUFFIX_CACHE_FILE = ".cache";
	private static final String SUFFIX_CACHE_TEMP_FILE=".cache.tmp";
	
	/**
	 * < (<cdrTime精确到分钟> << 32 | enb_id), fs>
	 * 每个时间点的文件读写handle map
	 */
	private Map<Long, OperatorFileSerial> fsMap = new HashMap<Long, OperatorFileSerial>();
	
	private boolean bInit = false;
	
	public static class CommonDataEntry {
		public int time;
		
		private int nGutiMMEC;
		private int nGutiMMEGI;
		
		private long imsi;
		private String msisdn;
		
		public Long getnGutiMMEC() {
			if (nGutiMMEC == 0xFFFFFFFF)
				return null;
			
			return (nGutiMMEC & 0xFFFFFFFFL);
		}
		
		public Long getnGutiMMEGI() {
			if (nGutiMMEGI == 0xFFFFFFFF)
				return null;
			
			return (nGutiMMEGI & 0xFFFFFFFFL);
		}
		
		public String getImsi() {
			if (imsi == 0xFFFFFFFFFFFFFFFFL)
				return null;
			
			return String.valueOf(imsi);
		}
		
		public String getMsisdn() {
			return msisdn;
		}
	}
	
	/**
	 * 每个时间点文件缓存，在文件中已排序好
	 * <enbGroupid, <cacheFileTime, <[enbid+enbUes1apId] Key, commonDatas>>>
	 */
	private Map<Integer, Map<Long, Map<Long, List<CommonDataEntry>>>> fileCaches = null; 
	
	/**
	 * 组装文件名
	 * @cacheFileDir
	 * 		缓存目录
	 * @param taskID
	 *      任务id
	 * @param nRecordTime
	 *		记录时间
	 * @parm groupIndex
	 * 		数据分组index
	 * @param rawFileTime
	 *  	文件时间(只在type==0时使用)
	 * @param type
	 *		文件类型
	 *  	
	 *  	[任务ID].[enb group id].[原始文件时间].cache.part
	 * @return
	 */
	protected static String makeCachePartFileName(String cacheFileDir, long taskID, 
			long nRecordTime, long groupIndex,
			Date rawFileTime) {
		StringBuilder sb = new StringBuilder();
		
		nRecordTime -= (nRecordTime % CACHE_FILE_UNIT_TIME);
		String timeStamp = TimeUtil.getDateString_yyyyMMddHHmm(new Date(nRecordTime));
		
		sb.append(cacheFileDir);
		sb.append(timeStamp).append(File.separator);
		
		//检测存储目录是否存在，如果不存在，则创建
		File fileDir = new File(sb.toString());
		if (!fileDir.exists() && !fileDir.isDirectory()) {
			synchronized (fileWriteLock) {
				fileDir.mkdir();
			}
		}
		
		sb.append(taskID);
		//sb.append(".").append(组装文件名timeStamp);
		sb.append(".").append(groupIndex);
		sb.append(".").append(TimeUtil.getDateString_yyyyMMddHHmmss(rawFileTime)).append(SUFFIX_PART_FILE);

		return sb.toString();
	}
	
	/**
	 * [任务ID].[enb group id].cache.tmp
	 * @param partFileName 	以一个原始核心网文件为单位，生产出来的缓存文件片
	 * @return
	 */
	protected static String makeCacheTempFileName(String partFileName) {
		if (partFileName == null)
			return null;
		
		int pos = partFileName.lastIndexOf(SUFFIX_PART_FILE);
		if (pos < 0)
			return null;
		
		String cacheTempFileName = partFileName.substring(0, pos);
		pos = cacheTempFileName.lastIndexOf('.');
		if (pos < 0)
			return null;
		
		cacheTempFileName = cacheTempFileName.substring(0, pos);
		
		return cacheTempFileName +  SUFFIX_CACHE_TEMP_FILE;
	}
	
	/**
	 * [任务ID].[enb group id].cache	
	 * @param cacheTempFileName 正在生成的临时缓存文件
	 * @return
	 */
	protected static String makeCacheFileName(String cacheTempFileName) {
		if (cacheTempFileName == null)
			return null;
		
		int pos = cacheTempFileName.lastIndexOf(SUFFIX_CACHE_TEMP_FILE);
		if (pos < 0)
			return null;
		
		return cacheTempFileName.substring(0, pos) +  SUFFIX_CACHE_FILE;
	}
	
	/**
	 * 从缓存文件全名中，获得缓存文件名
	 * @param cacheFileFullName
	 * @return
	 */
	protected static String getCacheFileTime(String cacheFileFullName) {
		String[] dirnames = cacheFileFullName.split("\\" + File.separator);
		if (dirnames.length>1) {
			return dirnames[dirnames.length-1];
		}
		
		return null;
	}
	
	/**
	 * 是否是可使用状态，如果config.ini中的
	 * system.lte.coreCommonData.cacheFileDir=none, 则为无效状态，不使用关联逻辑
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
		
		if (cacheFileDir != null && cacheFileDir.equalsIgnoreCase("none")) {
			bInit = false;
			return;
		}
		
		if (cacheFileDir == null || cacheFileDir.trim().length()<1 || cacheFileDir.indexOf("$")>=0) {
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
		
		this.timeWinOfMillSec = timeWinOfMinute * (60 * 1000L);
		this.validOfMillSec = validOfHour * (60 * 60 * 1000L);
		
		
		if (fileCaches == null) {
			fileCaches = new HashMap<Integer, Map<Long, Map<Long, List<CommonDataEntry>>>>();
		}
		
		bInit = true;
	}	

	/**
	 * 添加公共数据
	 * Long类型的参数很多是无符号的int,所以用long表示
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
	public int addCoreCommonData(Date rawFileTime, long recordTime, 
			Long mmeUeS1apID, 
			Long nGutiMMEGI, Long nGutiMMEC, Long nGutiMTMSI,
			Long imsi, String msisdn, 
			long taskID) throws Exception {
		
		if (!bInit)
			return -1;
		
		if ( (mmeUeS1apID == null || mmeUeS1apID == 0xFFFFFFFFL)
				&& (nGutiMTMSI==null || nGutiMTMSI == 0xFFFFFFFFL) )
			return -1;
		
		// 如果数据时间和文件时间相差N个小时，则丢弃
		if (Math.abs(recordTime - rawFileTime.getTime()) > timeWinOfMillSec) 
			return -2;
		
		long recordTimeIndex = recordTime / CACHE_FILE_UNIT_TIME;
		//long groupIndex = enbID / ENB_GROUP_COUNT;
		long groupIndex = DEFAULT_FILE_GROUP_INDEX;
		
		long fileIndexKey = ((recordTimeIndex & 0xFFFFFFFFL)<<32) | (groupIndex & 0xFFFFFFFFL);
		OperatorFileSerial fs = fsMap.get(fileIndexKey);
		if (fs == null) {
			String cachePartFile = makeCachePartFileName(this.cacheFileDir, taskID, 
					recordTime, groupIndex,
					rawFileTime);
			
			File file = new File(cachePartFile);
			if (file.exists())
				file.delete();

			fs = new OperatorFileSerial(EOPERATOR_FILE_MODE.e_Write, cachePartFile);
			fsMap.put(fileIndexKey, fs);
		}
		
//		Long key = makeHWCoreKey(enbID, enbUeS1apID);
//		fs.write(key);
		fs.write((int)(recordTime % CACHE_FILE_UNIT_TIME));
		fs.write((int)(mmeUeS1apID == null?0xFFFFFFFF:mmeUeS1apID));
		fs.write((int)(nGutiMTMSI == null?0xFFFFFFFF:nGutiMTMSI));
		fs.write((int)(nGutiMMEC == null?0xFFFFFFFF:nGutiMMEC));
		fs.write((int)(nGutiMMEGI == null?0xFFFFFFFF:nGutiMMEGI));
		fs.write(imsi == null?0xFFFFFFFFFFFFFFFFL:imsi);
		fs.write_fixedString(msisdn, 16);
		
		return 0;
	}
	
	/**
	 * 结束写缓存文件
	 * @param taskID
	 * @param rawFileTime
	 */
	public void endWrite(long taskID, Date rawFileTime) {
		if (!bInit)
			return ;
		
		Iterator<Entry<Long, OperatorFileSerial>> iter = fsMap.entrySet().iterator();
		while (iter.hasNext()) {
			Entry<Long, OperatorFileSerial> entry = iter.next();
			//long fileIndexKey = entry.getKey();
			OperatorFileSerial fs = entry.getValue();
			if (fs == null)
				continue;
			
			try {
				fs.flush();
				fs.close();
				
				String cachePartFileName = fs.getOperatorFileName();
				String cacheTmpFile = makeCacheTempFileName(cachePartFileName);
				// 将临时文件更改成正式的cache文件
				File fileSrc = new File(cachePartFileName);
				File fileDst = new File(cacheTmpFile);
				
				if (fileDst.exists() && fileDst.isFile()) {
					// 将cachePartFileName 合并到 cacheTmpFile
					String  timeTmpBakFile = cacheTmpFile + ".bak";
					File fileBak = new File(timeTmpBakFile);
					if (fileBak.exists() && fileBak.isFile()) {
						fileBak.delete();
					}
					
					//将临时缓存文件备份
					fileCopy(fileDst, fileBak);
					
					//合并当前的片断文件到备份文件中
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
		
		fsMap.clear();
		complateCacheFile(taskID, rawFileTime);
	}
	
	/**
	 *	缓存文件完成处理．
	 * @param taskID		任务id
	 * @param rawFileTime	原始文件时间
	 */
	protected void complateCacheFile(final Long taskID, Date rawFileTime) {
		final String filePrefix = taskID + ".";
		long nRawFileTime = rawFileTime.getTime();
		
		final long gtimeOutWinLeft = nRawFileTime - timeWinOfMillSec;
		final long gTimeInvalidLeft = nRawFileTime - validOfMillSec;
		
		//扫描cache下面的目录
		File cacheDirFile = new File(cacheFileDir);
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
					synchronized (fileWriteLock) {
						removeDir(timeDirName);
					}
					return false;
				}
				
				// 将已超时的缓存文件更改成正式名
				if (cacheFileTime <= gtimeOutWinLeft) {
					synchronized (fileWriteLock) {
						renameCacheTmpFile(timeDirName, filePrefix);
					}
				}

				return false;
			}
		});
	}
	
	private static void renameCacheTmpFile(String timeDirName, final String filePrefix) {
		File fileTimeDir = new File(timeDirName);
		fileTimeDir.list(new FilenameFilter() {
			@Override
			public boolean accept(File fileParentDIr, String fileName) {
				if (fileName.startsWith(filePrefix)) {
					if (fileName.endsWith(SUFFIX_CACHE_TEMP_FILE)) {
						/*String[] fileTag = fileName.split("\\.");
						if (fileTag.length<3)
							return false;*/
						
						// 将已超时的缓存文件更改成正式名
						String srcName = fileParentDIr.getAbsolutePath() + File.separator + fileName;
						String dstName = makeCacheFileName(srcName);
						File fileSrc = new File(srcName);
						File fileDst = new File(dstName);
						
						if (fileDst.exists() && fileDst.isFile())
							fileDst.delete();
						
						if (fileSrc.exists() && fileSrc.isFile()) 
							fileSrc.renameTo(fileDst);
						
						return false;
					}
				}
				
				return false;
			}
		});
		
		return;
	}
	
	/**
	 * 复制文件
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
	 * @param   sPath 被删除目录的文件路径 
	 * @return  目录删除成功返回true，否则返回false 
	 */  
	private void removeDir(String sPath) {  
	    //如果sPath不以文件分隔符结尾，自动添加文件分隔符  
	    if (!sPath.endsWith(File.separator)) {  
	        sPath = sPath + File.separator;  
	    }  
	    File fileDir = new File(sPath);  
	    //如果dir对应的文件不存在，或者不是一个目录，则退出  
	    if (!fileDir.exists() || !fileDir.isDirectory()) {  
	        return;  
	    }  
 
	    //删除文件夹下的所有文件(包括子目录)  
	    File[] files = fileDir.listFiles();  
	    for (int i = 0; i < files.length; i++) {  
	        //删除子文件  
	        if (files[i].isFile()) {  
	            FileUtil.removeFile(files[i].getAbsolutePath());  
	        } //删除子目录  
	        else {  
	            removeDir(files[i].getAbsolutePath());  
	        }  
	    }  
  
	    //删除当前目录  
	    fileDir.delete();
	}  
	
	private boolean sortCommonDataEntry(List<CommonDataEntry> entryList) {
		Collections.sort(entryList, new Comparator<CommonDataEntry> () {
			@Override
			public int compare(CommonDataEntry entry1, CommonDataEntry entry2) {
				if (entry1.time < entry2.time)
					return -1;
				
				if (entry1.time == entry2.time)
					return 0;
				
				return 1;
			}
		});
		
		return true;
	}
	
	/**
	 * 获取华为无线网的公共信息字段
	 * @param cdrTime
	 * @param mmeUeS1apID
	 * @param mtmsi
	 * @param mmegi
	 * @param mmec
	 * @return
	 */
	public CommonDataEntry getHWCdrCache(long cdrTime, 
			Long mmeUeS1apID, Long mtmsi, 
			Long mmegi, Long mmec) {
		if (!bInit)
			return null;
		
		//华为的cdr时间需要加一个默认8小时时区
		cdrTime += 8 * 60 * 60 * 1000L;
		return getCdrCache(cdrTime, mmeUeS1apID, mtmsi, mmegi, mmec);
	}

	/**
	 * 获取中兴无线网的公共信息字段
	 * @param cdrTime
	 * @param mmeUeS1apID
	 * @param mtmsi
	 * @param mmegi
	 * @param mmec
	 * @return
	 */
	public CommonDataEntry getZteCdrCache(long cdrTime, 
			Long mmeUeS1apID, Long mtmsi, 
			Long mmegi, Long mmec) {
		if (!bInit)
			return null;
		
		return getCdrCache(cdrTime, mmeUeS1apID, mtmsi, mmegi, mmec);
	}
	
	private CommonDataEntry getCdrCache(long cdrTime, 
			Long mmeUeS1apID, Long mtmsi, 
			Long mmegi, Long mmec) {
		// 根据cdrTime, 推测出自己需要获取的公共信息缓存文件应该在哪几个文件中
		long startTime = cdrTime - CACHE_FILE_TIME_RANGE;
		long endTime = (cdrTime + CACHE_FILE_TIME_RANGE);
		long fileStartTime = startTime - (startTime % CACHE_FILE_UNIT_TIME);
		long fileEndTime = endTime - (endTime % CACHE_FILE_UNIT_TIME);
		if ( (fileEndTime+CACHE_FILE_UNIT_TIME) < endTime ) {
			fileEndTime += CACHE_FILE_UNIT_TIME;
		}
		
		//Integer groupIndex = (int)(enbid / ENB_GROUP_COUNT);
		Integer groupIndex = DEFAULT_FILE_GROUP_INDEX;
		int timeCount = (int)((fileEndTime - fileStartTime) / CACHE_FILE_UNIT_TIME) + 1;
		//Long key = makeHWCoreKey(enbid, enbues1apid);
		Map<Long, Map<Long, List<CommonDataEntry>>> enbGroupCaches = null;
		synchronized (cacheReadLock) {
			enbGroupCaches = fileCaches.get(groupIndex);
			if (enbGroupCaches == null) {
				enbGroupCaches = new HashMap<Long, Map<Long, List<CommonDataEntry>>>();
				fileCaches.put(groupIndex, enbGroupCaches);
			}
		}
		
		for (int i = 0; i<timeCount; ++i) {
			Long fileTime = fileStartTime + i*CACHE_FILE_UNIT_TIME;
			//Date d = new Date(fileTime);
			Map<Long, List<CommonDataEntry>> caches = null; 
			synchronized (cacheReadLock) {
				caches = enbGroupCaches.get(fileTime);
			}
			
			// 如果在内存中的缓存为空，则从文件中缓存
			if (caches == null) {
				String [] cacheFiles = findCacheFile(fileTime, groupIndex);
				if (cacheFiles == null || cacheFiles.length < 1) {
					//缓存文件未生成
					continue;
				}
				
				caches = new HashMap<Long, List<CommonDataEntry>>();
				for (String cacheFile : cacheFiles) {
					if (readHWCdtCacheFile(caches, cacheFile) < 1) {
						continue;
					}
				}
				
				int enbGroupSize = 0;
				synchronized (cacheReadLock) {
					enbGroupCaches.put(fileTime, caches);
					enbGroupSize = enbGroupCaches.size();
				}
				
				LOGGER.debug("CoreCommonDataManager load cdr cache. enbGroupIndex:{} fileTime:{}　缓存总数:{}"
						, new Object[]{groupIndex, TimeUtil.getDateString_yyyyMMddHHmm(new Date(fileTime)), enbGroupSize});
			}
			
			// 关联
			if (mtmsi != null && mtmsi != 0xFFFFFFFFL) {
				CommonDataEntry entry = matchKeyByCommonDataEntryList(caches, mtmsi, mmec, mmegi, fileTime ,startTime, endTime);
				if (entry != null)
					return entry;
			}
			
			if (mmeUeS1apID != null && mmeUeS1apID != 0xFFFFFFFFL) {
				CommonDataEntry entry = matchKeyByCommonDataEntryList(caches, (mmeUeS1apID<<32), mmec, mmegi, fileTime ,startTime, endTime);
				if (entry != null)
					return entry;
			}
		}
		
		return null;
	}
	
	private CommonDataEntry matchKeyByCommonDataEntryList(
			Map<Long, List<CommonDataEntry>> caches, 
			Long key, Long mmec, Long mmegi, 
			Long fileTime, long startTime, long endTime) {
		List<CommonDataEntry> commonDataEntryList = caches.get(key);
		if (commonDataEntryList == null)
			return null;
		
		// 排序
		this.sortCommonDataEntry(commonDataEntryList);
		
		// 关联
		for (CommonDataEntry entry: commonDataEntryList) {
			long entryTime = entry.time + fileTime;
			if (entryTime < startTime)
				continue; 
			
			if (entryTime > endTime)
				break;
			
			if ((mmec != null && mmec.equals(entry.getnGutiMMEC())) 
					&& (mmegi != null && mmegi.equals(entry.getnGutiMMEGI()))) {
				return entry;
			}
		}
		
		return null;
	}
	
	/**
	 * 获得最大的缓存数据时间
	 * @return
	 */
	public Date getMaxCacheDateTime() {
		if (!bInit)
			return null; 
		
		String maxDirName = null;
		File cacheDirFile = new File(cacheFileDir);
		String[] dirnames = cacheDirFile.list();
		for (String dirname : dirnames) {
			if (dirname.length() != 12)
				continue;
			
			if (maxDirName == null) {
				maxDirName = dirname;
				continue;
			}
			
			if (maxDirName.compareTo(dirname)<0)
				maxDirName = dirname;
		}
		
		if (maxDirName == null)
			return null;
		
		try {
			Date d = TimeUtil.getyyyyMMddHHmmDate(maxDirName);
			return d;
		} catch (ParseException e) {
		}
		
		return null;
	}
	
	public boolean isCacheReady(Date cdrFileTime) {
		Date maxCacheFileTime = getMaxCacheDateTime();
		if ((maxCacheFileTime==null)||((maxCacheFileTime.getTime() - timeWinOfMillSec) < cdrFileTime.getTime())) {
			return false;
		}
		
		return true;
	}
	
	/**
	 * 释放enbgroup id的缓存
	 * @param enbid
	 * @param nMaxReservedCacheBlock 最大保留的内存块
	 */
	public void releaseEnbCache(long enbid, int nMaxReservedCacheBlock) {
		if (!bInit)
			return ;
		
		//Integer enbGroupIndex = (int)(enbid / ENB_GROUP_COUNT);
		Integer enbGroupIndex = DEFAULT_FILE_GROUP_INDEX;
		Map<Long, Map<Long, List<CommonDataEntry>>> enbGroupCaches = fileCaches.get(enbGroupIndex);
		if (enbGroupCaches == null || enbGroupCaches.size() <= nMaxReservedCacheBlock) 
			return;
		
		//MAX_CACHE_IN_MEMORY
		List<Long> cacheFileTimeList = new LinkedList<Long>();
		synchronized (cacheReadLock) {
			Iterator<Entry<Long, Map<Long, List<CommonDataEntry>>>> iter = enbGroupCaches.entrySet().iterator();
			while (iter.hasNext()) {
				Entry<Long, Map<Long, List<CommonDataEntry>>> entry = iter.next();
				cacheFileTimeList.add(entry.getKey());
			}
		}
		
		Collections.sort(cacheFileTimeList);
		int nRemoveBlockSize = cacheFileTimeList.size() - nMaxReservedCacheBlock;
		synchronized (cacheReadLock) {
			for (int i=0; i<nRemoveBlockSize; ++i) {
				Long minCacheFileTime = cacheFileTimeList.remove(0);
				if (minCacheFileTime != null) {
					enbGroupCaches.remove(minCacheFileTime);
				}
			}
		}
		
		LOGGER.debug("CoreCommonDataManager.releaseEnbCache() enbGroupIndex:{}，本次共释放：{}, 释放后内存块驻留内存个数:{}", new Object[]{enbGroupIndex, nRemoveBlockSize, cacheFileTimeList.size()});
	}
	
	/**
	 * 释放enbgroup id的缓存
	 * @param enbid
	 */
	public void releaseEnbCache(long enbid) {
		releaseEnbCache(enbid, MAX_CACHE_BLOCK_IN_MEMORY);
	}
	
	private String[] findCacheFile(final long readCacheFileTime, final Integer groupIndex) {
		String timeStamp = TimeUtil.getDateString_yyyyMMddHHmm(new Date(readCacheFileTime));
		
		String timeCacheFileDir = cacheFileDir + timeStamp + File.separator;
		File cacheDirFile = new File(timeCacheFileDir);
		String [] files = cacheDirFile.list(new FilenameFilter() {
			@Override
			public boolean accept(File fileDir, String fileName) {
				if (fileName.endsWith(SUFFIX_CACHE_FILE)) {
					String[] fileTag = fileName.split("\\.");
					if (fileTag.length<3)
						return false;
					
					//String cacheFileTimeStr = getCacheFileTime(fileDir + File.separator + fileName);
					//long cacheFileTime = 0;
					Integer nFileGroupIndex = null;
					try {
						//cacheFileTime = TimeUtil.getyyyyMMddHHmmDate(cacheFileTimeStr).getTime();
						nFileGroupIndex = Integer.parseInt(fileTag[1]);
					} catch (Exception e) {
						return false;
					}
					
					if (!nFileGroupIndex.equals(groupIndex))
						return false;
					
					return true;
				}
				
				return false;
			}
		});
		
		if (files != null) {
			for (int i=0; i<files.length; ++i) {
				files[i] = timeCacheFileDir + files[i];
			}
		}
		
		return files;
	}
	
	private long readHWCdtCacheFile(Map<Long, List<CommonDataEntry>> cacheMap, String cacheFile) {
		long startTime = System.currentTimeMillis();
		long entrySize = 0;
		OperatorFileSerial fs = null;
		try {
			fs = new OperatorFileSerial(EOPERATOR_FILE_MODE.e_Read, cacheFile);
			//List<CommonDataEntry> commonDataEntryList = null;
			while (!fs.isEndOfFile()) {
				CommonDataEntry entry = new CommonDataEntry();
				entry.time = fs.read_int();
				long mmeUeS1apID = fs.read_uint();
				long nGutiMTMSI = fs.read_uint();
				entry.nGutiMMEC = fs.read_int();
				entry.nGutiMMEGI = fs.read_int();
				entry.imsi = fs.read_long();
				entry.msisdn = fs.read_string(16, true);
				
				if (mmeUeS1apID != 0xFFFFFFFFL) {
					long key = (mmeUeS1apID << 32);
					List<CommonDataEntry> commonDataEntryList = cacheMap.get(key);
					if (commonDataEntryList == null) {
						commonDataEntryList = new LinkedList<CommonDataEntry>();
						cacheMap.put(key, commonDataEntryList);
					}
					commonDataEntryList.add(entry);
				}
				
				if (nGutiMTMSI != 0xFFFFFFFFL) {
					long key = (nGutiMTMSI & 0xFFFFFFFFL);
					List<CommonDataEntry> commonDataEntryList = cacheMap.get(key);
					if (commonDataEntryList == null) {
						commonDataEntryList = new LinkedList<CommonDataEntry>();
						cacheMap.put(key, commonDataEntryList);
					}
					commonDataEntryList.add(entry);
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
				} catch (Exception e) {}
			}
		}
		
		LOGGER.debug("cacheFile:{} recordCount:{} loadTimeElapse:{}", new Object[]{cacheFile, entrySize, (System.currentTimeMillis()-startTime)});
		return entrySize;
	}
	
	public String getCacheFileDir() {
		return cacheFileDir;
	}
	
	public void setCacheFileDir(String cacheFileDir) {
		this.cacheFileDir = cacheFileDir;
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
	
	public static long makeHWCoreKey(long enbid, long enbues1apid) {
		long key = ((enbid & 0xFFFFFFFFL) << 32) | (enbues1apid & 0xFFFFFFFFL);
		return key;
	}
	
	public static long parseEnbID(long key) {
		return (key>>32) & 0xFFFFFFFFL;
	}
	
	public static long parseEnbueS1apID(long key) {
		return key & 0xFFFFFFFFL;
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

	/**
	 * @param args
	 * @throws Exception
	 */
	@SuppressWarnings("unused")
	public static void main2(String[] args) throws Exception {
		File f = new File("/home/shig/mrCache/25/");
		f.mkdir();
		
		CommonDataEntry[] entrs1 = new CommonDataEntry[1000];
		CommonDataEntry[] entrs2 = new CommonDataEntry[10000];
		CommonDataEntry[] entrs3 = new CommonDataEntry[1000000];
		
		CoreCommonDataManager manager = new CoreCommonDataManager();
		String cacheFiles[] = {
				"/home/shig/mrCache/10408081301.201503300850.cache",
				"/home/shig/mrCache/10408081301.201503300855.cache",
				"/home/shig/mrCache/10408081301.201503300900.cache",
		};
		Map<Long, List<CommonDataEntry>> caches = new HashMap<Long, List<CommonDataEntry>>();
		long startTime = System.currentTimeMillis();
		for (String cacheFile : cacheFiles) {
			if (manager.readHWCdtCacheFile(caches, cacheFile) < 1) {
				return;
			}
		}
		System.out.println("time elapse:" + (System.currentTimeMillis()-startTime));
	}
	
	@SuppressWarnings("unused")
	public static void main(String[] args) {
		int i1= 025;
		final String filePrefix = "10408081301.";
		//扫描cache下面的目录
		File cacheDirFile = new File("/home/shig/mrCache/lteCoreCommonData");
		cacheDirFile.list(new FilenameFilter() {
			@Override
			public boolean accept(File fileParentDIr, String fileName) {
				if (fileName.length() != 12)
					return false;
				
				String timeDirName = fileParentDIr.getAbsolutePath() + File.separator + fileName;
				
				renameCacheTmpFile(timeDirName, filePrefix);

				return false;
			}
		});
	}
	
	
}
