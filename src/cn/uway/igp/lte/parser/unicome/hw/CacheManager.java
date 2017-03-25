package cn.uway.igp.lte.parser.unicome.hw;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.apache.commons.net.ftp.FTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.FTPAccessor;
import cn.uway.framework.connection.FTPConnectionInfo;
import cn.uway.framework.job.AbstractJob;
import cn.uway.framework.task.Task;
import cn.uway.igp.lte.extraDataCache.ExtraDataUtil;

public class CacheManager {
	private static Logger LOG = LoggerFactory.getLogger(CacheManager.class);
	private static final String NODEB_FUNCTION = "BTS3900NODEBFUNCTION";
	private static final String USER_LABEL = "USERLABEL";

	public static String getUserLabel(Task task, AbstractJob job,
			String nodebName) {
		String rawData = task.getGatherPathDescriptor().getRawData();
		int idx = rawData.lastIndexOf("/");
		rawData = rawData.substring(0, idx + 1) + "CMExport_" + nodebName
				+ "*.xml";
		InputStream inputstream = null;
		FTPClient client = null;
		try {
			FTPConnectionInfo ftpInfo = (FTPConnectionInfo) job
					.getConnectionInfo();
			FTPAccessor acc = (FTPAccessor) job.getAccessor();
			client = acc.getFtpPool().getFTPClient();
			inputstream = ExtraDataUtil.retriveFile(client, rawData,
					ftpInfo.getCharset());
			if (inputstream == null) {
				LOG.info("没找到文件:{}", rawData);
				return null;
			}
			BufferedReader bufferdReader = new BufferedReader(new InputStreamReader(
					inputstream));
			String lineInfo = null;
			Boolean isFind = false;
			while ((lineInfo = bufferdReader.readLine()) != null) {
				if (lineInfo.contains(NODEB_FUNCTION)) {
					isFind = true;
				}
				if (isFind && lineInfo.contains(USER_LABEL)) {
					bufferdReader.close();
					return lineInfo.substring(lineInfo.indexOf("\">") + 2,
							lineInfo.indexOf("</attr>"));
				}
			}
		} catch (Exception e) {
			LOG.info("文件下载失败", e);
		} finally {
			ExtraDataUtil.closeFtpStream(null, inputstream, client);
		}
		LOG.info("文件中没有唯一标识:{}", rawData);
		return null;
	}
}
