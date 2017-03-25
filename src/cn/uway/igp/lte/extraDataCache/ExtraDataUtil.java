package cn.uway.igp.lte.extraDataCache;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipInputStream;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.util.FTPUtil;
import cn.uway.util.IoUtil;
import cn.uway.util.StringUtil;

public class ExtraDataUtil {

	public static final Logger LOGGER = LoggerFactory.getLogger(ExtraDataUtil.class);

	/**
	 * 记载指定FTP服务器上最新的数据文件 返回一个InputStream
	 * 
	 * @param client
	 * @param remoteFileName
	 * @return
	 * @throws Exception
	 */
	public static InputStream retriveFile(FTPClient client, String remotePath) throws Exception {
		List<FTPFile> remoteFiles = FTPUtil.listFilesRecursive(client, remotePath);
		if (remoteFiles == null || remoteFiles.isEmpty()) {
			LOGGER.warn("在服务器上未找到匹配文件!remotePath=" + remotePath);
			return null;
		}
		List<String> remoteFileName = new ArrayList<String>();
		for (FTPFile ftpFile : remoteFiles) {
			remoteFileName.add(ftpFile.getName());
		}
		Collections.sort(remoteFileName);

		String file = null;
		if (!remoteFileName.isEmpty())
			file = remoteFileName.get(remoteFileName.size() - 1);
		LOGGER.debug("排序后确定使用的FTP文件：{}", file);
		return FTPUtil.download(file, client);
	}
	/**
	 * 记载指定FTP服务器上最新的数据文件 返回一个InputStream
	 * 
	 * @param client
	 * @param remoteFileName
	 * @param encode
	 * @return
	 * @throws Exception
	 */
	public static InputStream retriveFile(FTPClient client, String remotePath, String encode) throws Exception {
		List<FTPFile> remoteFiles = FTPUtil.listFilesRecursive(client, remotePath, encode);
		if (remoteFiles == null || remoteFiles.isEmpty()) {
			LOGGER.warn("在服务器上未找到匹配文件!remotePath=" + remotePath);
			return null;
		}
		List<String> remoteFileName = new ArrayList<String>();
		for (FTPFile ftpFile : remoteFiles) {
			remoteFileName.add(ftpFile.getName());
		}
		Collections.sort(remoteFileName);
		
		String file = null;
		if (!remoteFileName.isEmpty())
			file = remoteFileName.get(remoteFileName.size() - 1);
		String decodeName = StringUtil.decodeFTPPath(file, encode);
		LOGGER.debug("排序后确定使用的FTP文件：{}", decodeName);
		return FTPUtil.download(file, client);
	}

	// 关闭FTP和输入流
	// 此处FTPClient有可能
	public static void closeFtpStream(ZipInputStream zipStream, InputStream in, FTPClient client) {
		// 关闭Zip流
		IoUtil.closeQuietly(zipStream);

		// 关闭输入流
		IoUtil.closeQuietly(in);

		// 关闭FTP
		LOGGER.debug("[Cache]准备获取FTP响应...");
		FTPUtil.completePendingCommandNoEx(client);
		LOGGER.debug("[Cache]获取FTP响应完成。");
		FTPUtil.logoutAndCloseFTPClient(client);
	}
}
