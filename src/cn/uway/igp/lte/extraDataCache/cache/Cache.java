package cn.uway.igp.lte.extraDataCache.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.connection.FTPConnectionInfo;
import cn.uway.framework.context.AppContext;
import cn.uway.framework.external.AbstractCache;
import cn.uway.igp.lte.context.common.CommonSystemConfigMgr;

public class Cache extends AbstractCache implements Runnable {

	// 日志
	protected final static Logger LOGGER = LoggerFactory.getLogger(Cache.class);

	// 加载因子
	protected static final float factor = AppContext.getBean("extraDataFactor", Float.class);

	// FTP连接信息用于连接FTP服务器
	protected static FTPConnectionInfo connectionInfo = CommonSystemConfigMgr.getExtraDataServiceFTP();
	
	public Cache() {
		super();
	}

	// 初始化设置ftp连接最大等待时间 maxWaitSecond
	public static void init() {
		if (isTurnOnMaxWaitSecond) {
			if (connectionInfo != null && connectionInfo.getMaxWaitSecond() <= 0)
				connectionInfo.setMaxWaitSecond(maxWaitSecond);
		}
	}

	@Override
	public void run() {
	}
}
