package cn.uway.igp.lte.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.commons.dbcp.BasicDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.connection.FTPConnectionInfo;
import cn.uway.framework.log.ImportantLogger;
import cn.uway.igp.lte.context.common.pojo.CommonSystemConfig;
import cn.uway.util.DbUtil;

/**
 * 加载LTE公共配置 LteSystemConfigDAO 如在该表中配置了公共信息 但是加载失败，程序将不能启动
 * 
 * @author chenrongqiang 2012-11-9
 */
public class LteSystemConfigDAO {

	private static Logger logger = LoggerFactory.getLogger(LteSystemConfigDAO.class); // 日志

	/**
	 * 读取LTE公共配置SQL语句
	 */
	private String sqlForLteExtraDataServiceFTP;

	/**
	 * @return the sqlForLteExtraDataServiceFTP
	 */
	public String getSqlForLteExtraDataServiceFTP() {
		return sqlForLteExtraDataServiceFTP;
	}

	/**
	 * @param sqlForLteExtraDataServiceFTP the sqlForLteExtraDataServiceFTP to set
	 */
	public void setSqlForLteExtraDataServiceFTP(String sqlForLteExtraDataServiceFTP) {
		this.sqlForLteExtraDataServiceFTP = sqlForLteExtraDataServiceFTP;
	}

	/**
	 * 使用framewoke提供数据库连接数据源
	 */
	private BasicDataSource datasource;

	/**
	 * @return the datasource
	 */
	public BasicDataSource getDatasource() {
		return datasource;
	}

	/**
	 * @param datasource the datasource to set
	 */
	public void setDatasource(BasicDataSource datasource) {
		this.datasource = datasource;
	}

	/**
	 * 查询公共配置 如查询失败 程序终止
	 * 
	 * @return
	 */
	public CommonSystemConfig getCommonConfg() {
		logger.debug("开始执行LTE公共配置项加载!");
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		CommonSystemConfig commonSystemConfig = null;
		try {
			conn = datasource.getConnection();
			statement = conn.prepareStatement(sqlForLteExtraDataServiceFTP);
			rs = statement.executeQuery();
			while (rs.next()) {
				commonSystemConfig = new CommonSystemConfig();
				commonSystemConfig.setBaseSummaryFileExt(rs.getString("BASE_SUMMARY_FILE_EXT"));
				commonSystemConfig.setBaseSummaryFileSplit(rs.getString("BASE_SUMMARY_FILE_SPLIT"));
				commonSystemConfig.setBaseSummaryOkFileExt(rs.getString("BASE_SUMMARY_OK_FILE_EXT"));
				commonSystemConfig.setBaseSummaryOkFileScanPeriod(rs.getInt("OK_FILE_SCAN_PERIOD"));
				commonSystemConfig.setNeNotExistIgnore(rs.getInt("NE_NOT_EXIST_IGNORE") == 1);
				commonSystemConfig.setNeReloadSchedule(rs.getInt("NE_RELOAD_SCHEDULE"));
				// 读取外部FTP配置
				commonSystemConfig.setConnectionInfo(getExtraFTP(rs));
				logger.debug("LTE公共配置表igp_lte_cdl_cfg_system查询成功!");
				logger.debug("LTE公共配置如下:" + commonSystemConfig.toString());
			}
			if (commonSystemConfig == null) {
				ImportantLogger.getLogger().warn("LTE公共配置表igp_lte_cdl_cfg_system未配置!");
				ImportantLogger.getLogger().warn("LTE网数据采集程序即将停止!");
				System.exit(0);
				logger.warn("LTE网数据采集程序已停止!请检查配置项后再重新启动!");
			}
		} catch (SQLException e) {
			ImportantLogger.getLogger().warn("LTE公共配置表igp_lte_cdl_cfg_system查询失败!", e);
			ImportantLogger.getLogger().warn("LTE网数据采集程序即将停止!");
			System.exit(0);
			logger.warn("LTE网数据采集程序已停止!请检查配置项后再重新启动!");
		} finally {
			DbUtil.close(rs, statement, conn);
		}
		return commonSystemConfig;
	}

	/**
	 * 从结果集中获取FTP连接信息
	 * 
	 * @param rs
	 * @return ExtraDataServiceFTP
	 */
	private FTPConnectionInfo getExtraFTP(ResultSet rs) {
		try {
			FTPConnectionInfo connInfo = new FTPConnectionInfo();
			connInfo.setUserName(rs.getString("USER_NAME"));
			connInfo.setPassword(rs.getString("USER_PWD"));
			connInfo.setPort(rs.getInt("PORT"));
			connInfo.setCharset(rs.getString("CHARSET"));
			connInfo.setPassiveFlag(rs.getInt("PASSIVE") == 1 ? true : false);
			connInfo.setMaxConnections(rs.getInt("MAX_CONNECTIONS"));
			connInfo.setMaxWaitSecond(rs.getInt("MAX_WAIT_SECOND"));
			connInfo.setValidateCmd(rs.getString("VALIDATE_CMD"));
			connInfo.setIp(rs.getString("IP"));
			return connInfo;
		} catch (Exception e) {
			return null;
		}
	}
}
