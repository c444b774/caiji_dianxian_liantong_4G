package cn.uway.igp.lte.parser.mr;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.accessor.StreamAccessOutObject;
import cn.uway.framework.parser.AbstractParser;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.util.IoUtil;
import cn.uway.util.TimeUtil;


public class FileDownloadParser extends AbstractParser {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(FileDownloadParser.class);
	/**
	 * 文件下载的基础目录
	 */
	private static final String SAVE_BASE_PATH = "./download/";
	
	/**
	 * 文件在本地保存的时间(3天) 
	 */
	long timeSave = 1*(24*60*60*1000L);
	
	// 清除本地已过时的文件
	protected int dropHistoryFiles(String dir, long timeDropBefore, int level) {
		int nDropCount = 0;
		File fileDropDir = new File(dir);
		File[] files =  fileDropDir.listFiles();
		for (File file : files) {
			if (file.lastModified() < timeDropBefore) {
				if (file.isFile()) {
					if (file.delete()) {
						++nDropCount;
					}
				} else {
					nDropCount += dropHistoryFiles(dir + "/" + file.getName(), timeDropBefore, level+1);
				}
			}
		}
		
		if (level > 0) {
			if (fileDropDir.listFiles().length < 1) {
				fileDropDir.delete();
			}
		}
		
		return nDropCount;
	}
	
	
	
	
	public final void downLoadRawFile(InputStream in, String outRawFileName) throws Exception {
		String saveDir = outRawFileName.substring(0, outRawFileName.lastIndexOf('/')); 
		File fileDir = new File(saveDir);
		if (!fileDir.exists() || !fileDir.isDirectory()) {
			LOGGER.debug("创建本地存储文件夹：{}", saveDir);
			if (!fileDir.mkdirs()) {
				LOGGER.error("创建存储路径:{}失败!", saveDir);
				throw new Exception("创建存储路径失败");
			}
		}
		
		File targetFile = new File(outRawFileName + ".tmp");
		OutputStream output = null;
		try {
			output = new FileOutputStream(targetFile);
			IOUtils.copy(in, output);
			output.flush();
			IoUtil.closeQuietly(output);
			targetFile.renameTo(new File(outRawFileName));
		} catch (Exception e) {
			targetFile.delete();
			throw e;
		} finally {
			IoUtil.closeQuietly(output);
		}
	}
	
	
	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		StreamAccessOutObject streamOut = (StreamAccessOutObject) accessOutObject;
		String rawSubFile = accessOutObject.getRawAccessName();
		String rawPackFile = accessOutObject.getRawAccessPackName();
		
		String saveDir = SAVE_BASE_PATH  + task.getId();
		
		/*if (!rawPackFile.equalsIgnoreCase(rawSubFile)) {
			saveDir = saveDir + "/" + FileUtil.getFileName(rawPackFile);
		}*/
		String targetFile = saveDir + "/" + rawSubFile;
		
		LOGGER.debug("开始下载:{}至：{}, 从文件包:{}", new Object[] {rawSubFile, targetFile, rawPackFile});
		downLoadRawFile(streamOut.getOutObject(), targetFile);
		LOGGER.debug("文件:{}下载成功.", targetFile);
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		return null;
	}

	@Override
	public List<ParseOutRecord> getAllRecords() {
		return null;
	}

	@Override
	public void close() {
		String saveDir = SAVE_BASE_PATH  + task.getId();
		long timeDropBefore = System.currentTimeMillis() - timeSave;
		LOGGER.debug("开始清理\"{}\"目录下，日期在：\"{}\"前的所有文件.", saveDir, TimeUtil.getDateString(new Date(timeDropBefore)));
		dropHistoryFiles(saveDir, timeDropBefore, 0);
	}

	@Override
	public Date getDataTime(ParseOutRecord outRecord) {
		return null;
	}
	
	

}
