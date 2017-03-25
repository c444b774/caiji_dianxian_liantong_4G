package cn.uway.igp.lte.context.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.connection.FTPConnectionInfo;
import cn.uway.igp.lte.context.common.pojo.CommonSystemConfig;
import cn.uway.igp.lte.dao.LteSystemConfigDAO;

/**
 * 缓存CDMA IGP公共配置项 这部分配置项主要是为了减少现场部署复杂度 但是只在程序启动的时候进行加载 CommonSystemConfigMgr
 * 
 * @author chenrongqiang 2012-11-9
 */
public class CommonSystemConfigMgr {

	private static Logger logger = LoggerFactory.getLogger(CommonSystemConfigMgr.class); // 日志

	/**
	 * 公共配置
	 */
	private static CommonSystemConfig systemConfig = null;

	private LteSystemConfigDAO lteSystemConfigDAO;

	/**
	 * @return the lteSystemConfigDAO
	 */
	public LteSystemConfigDAO getLteSystemConfigDAO() {
		return lteSystemConfigDAO;
	}

	/**
	 * @param lteSystemConfigDAO the lteSystemConfigDAO to set
	 */
	public void setLteSystemConfigDAO(LteSystemConfigDAO lteSystemConfigDAO) {
		this.lteSystemConfigDAO = lteSystemConfigDAO;
	}

	/**
	 * 执行数据systemConfig初始化 如初始化失败 程序将不能启动
	 */
	public void loadDBConfig() {
		if (systemConfig == null) {
			systemConfig = lteSystemConfigDAO.getCommonConfg();
			logger.debug(" 读取LTE公共配置成功!");
		}
	}

	/**
	 * 获取系统配置
	 * 
	 * @return 如果关联网元失败 是否需要输出到原始话单 0表示不输出 即写入BAD文件 1表示正常输出
	 */
	public static boolean isNeNotExistIgnore() {
		return systemConfig.isNeNotExistIgnore();
	}

	/**
	 * 获取系统配置
	 * 
	 * @return 网元数据每天加载的时间点，取值0-23
	 */
	public static int getNeReloadSchedule() {
		return systemConfig.getNeReloadSchedule();
	}

	/**
	 * 获取系统配置
	 * 
	 * @return 输出给汇总的中间文件的后缀名
	 */
	public static String getBaseSummaryFileExt() {
		return systemConfig.getBaseSummaryFileExt();
	}

	/**
	 * 获取系统配置
	 * 
	 * @return 输出给汇总的中间文件数据中各field的分隔符
	 */
	public static String getBaseSummaryFileSplit() {
		return systemConfig.getBaseSummaryFileSplit();
	}

	/**
	 * 获取系统配置
	 * 
	 * @return 完整性ok文件规则扫描周期，单位分钟
	 */
	public static int getBaseSummaryOkFileScanPeriod() {
		return systemConfig.getBaseSummaryOkFileScanPeriod();
	}

	/**
	 * 获取系统配置
	 * 
	 * @return 输出给汇总的完整性控制文件的后缀名
	 */
	public static String getBaseSummaryOkFileExt() {
		return systemConfig.getBaseSummaryOkFileExt();
	}

	public static FTPConnectionInfo getExtraDataServiceFTP() {
		return systemConfig.getConnectionInfo();
	}
}
