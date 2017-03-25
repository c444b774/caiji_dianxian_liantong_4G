package cn.uway.igp.lte.extraDataCache.cache;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.dbcp.BasicDataSource;

import cn.uway.framework.external.AbstractCache;
import cn.uway.util.DbUtil;
import cn.uway.util.StringUtil;

/**
 * 网元缓存
 * 
 * @author yuy
 * @Date 15 Apr, 2014
 */
public class NeInfoCache extends AbstractCache implements Runnable {

	/** 数据源 **/
	private BasicDataSource dataSource;

	/** 查询CELL表SQL */
	private String sqlForGetCellRecords;

	/** 查询ENODB表SQL */
	private String sqlForGetEnbRecords;

	/** 网元数据缓存 */
	public static Map<String, Map<String, String>> NECACHER = new ConcurrentHashMap<String, Map<String, String>>();

	/** 网元关联字段映射 */
	private static Map<String, String> INDEXKEYSMAP = new ConcurrentHashMap<String, String>();

	/** 加载定时器 **/
	private static Timer timer;

	/** 网元定时加载线程 **/
	private static Thread loadThread;

	/** 需要加载网元的MMEID，spring注入 **/
	public String mmeIds;

	/** 网元的关联字段组合，spring注入 **/
	public String relationShipKeysGroup;

	/** enodB级别的关联关键字组合 **/
	public List<String[]> enodbKeysList;

	/** cell级别的关联关键字组合 **/
	public List<String[]> cellKeysList;

	@Override
	public void run() {
		long min = period / 1000 / 60;
		LOGGER.debug("网元定时加载线程启动，{}分钟后开始执行，执行周期为{}分钟", new Object[]{min, min});
		timer = new Timer("ne_info_loader");
		timer.schedule(new ReloadTimerTask(), period, period);
	}

	/**
	 * 重载定时器任务
	 */
	class ReloadTimerTask extends TimerTask {

		public void run() {
			load();
		}
	}

	/**
	 * 加载一次，然后开启定时线程
	 */
	public void start() {
		// 验证网元配置项
		if (!validateNeConfig()) {
			LOGGER.debug("网元定时加载线程启动失败.");
			return;
		}
		// 开始加载
		load();
		synchronized (NeInfoCache.class) {
			if (loadThread == null) {
				NeInfoCache instance = new NeInfoCache();
				loadThread = new Thread(instance, "网元定时加载线程");
				loadThread.start();
			}
		}
	}

	/**
	 * 从数据库中加载网元信息
	 */
	public void load() {
		LOGGER.debug("开始加载网元基础信息.");
		long current = System.currentTimeMillis();
		Connection conn = null;
		try {
			conn = dataSource.getConnection();
			// CELL
			if (cellKeysList != null && cellKeysList.size() > 0) {
				getCellRecords(conn);
			}
			// ENODB
			if (enodbKeysList != null && enodbKeysList.size() > 0) {
				getEnodbRecords(conn);
			}
		} catch (Exception e) {
			LOGGER.error("网元信息加载出现异常", e);
		} finally {
			DbUtil.close(null, null, conn);
			if (isEmpty())
				LOGGER.debug("没有加载到网元信息.");
			else
				LOGGER.debug("网元信息加载成功.");
		}

		LOGGER.debug("加载网元信息结束,共加载{}条,用时{}秒.", getSize(), (System.currentTimeMillis() - current) / 1000L);

	}

	/**
	 * 获取小区网元信息
	 * 
	 * @param conn
	 * @throws SQLException
	 */
	public void getCellRecords(Connection conn) throws SQLException {
		LOGGER.debug("开始加载CELL级别的网元数据");
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		String sql = null;
		int sum = 0;
		Map<String, String> map = null;
		sql = this.sqlForGetCellRecords;
		sql = sql.replace("?", mmeIds);
		LOGGER.debug("查询任务的SQL为：{}", sql);
		try {
			pstmt = conn.prepareStatement(sql);
			rs = pstmt.executeQuery();
			ResultSetMetaData rsmd = rs.getMetaData();
			int count = rsmd.getColumnCount();
			while (rs.next()) {
				map = new HashMap<String, String>();
				for (int n = 1; n <= count; n++) {
					map.put(rsmd.getColumnName(n), rs.getString(n));
				}
				String cellKeys = getCellKeys(map);
				INDEXKEYSMAP.put(cellKeys, getLocalCellKeys(map));
				for (String[] array : cellKeysList) {
					NECACHER.put(getMyKey(map, array), map);
				}
				sum++;
			}
		} catch (Exception e) {
			LOGGER.error("查询NE_CELL_L出现异常", e);
		} finally {
			DbUtil.close(rs, pstmt, null);
		}
		LOGGER.debug("CELL级别的网元数据加载结束，共加载{}条", sum);
	}

	/**
	 * 获取ENODB网元信息
	 * 
	 * @param conn
	 * @throws SQLException
	 */
	public void getEnodbRecords(Connection conn) throws SQLException {
		LOGGER.debug("开始加载ENODB级别的网元数据");
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		String sql = null;
		int sum = 0;
		Map<String, String> map = null;
		sql = this.sqlForGetEnbRecords;
		sql = sql.replace("?", mmeIds);
		LOGGER.debug("查询任务的SQL为：{}", sql);
		try {
			pstmt = conn.prepareStatement(sql);
			rs = pstmt.executeQuery();
			ResultSetMetaData rsmd = rs.getMetaData();
			int count = rsmd.getColumnCount();
			while (rs.next()) {
				map = new HashMap<String, String>();
				for (int n = 1; n <= count; n++) {
					map.put(rsmd.getColumnName(n), rs.getString(n));
				}
				for (String[] array : enodbKeysList) {
					NECACHER.put(getMyKey(map, array), map);
				}
				sum++;
			}
		} catch (Exception e) {
			LOGGER.error("查询NE_ENB_L出现异常", e);
		} finally {
			DbUtil.close(rs, pstmt, null);
		}
		LOGGER.debug("ENODB级别的网元数据结束，共加载{}条", sum);
	}

	public Map<String, String> getNeInfo(String EQU_MME_ID, String ENB_ID) {
		if (NECACHER.size() == 0)
			return null;
		return NECACHER.get(EQU_MME_ID + "-" + ENB_ID);
	}

	public Map<String, String> getNeInfo(String EQU_MME_ID, String ENB_ID, String CELL_ID) {
		if (NECACHER.size() == 0)
			return null;
		return NECACHER.get(EQU_MME_ID + "-" + ENB_ID + "-" + CELL_ID);
	}

	/**
	 * 获取网元信息。
	 * 
	 * @param key
	 * @return NeInfo
	 */
	public Map<String, String> getNeInfo(String key) {
		if (NECACHER.size() == 0)
			return null;
		return NECACHER.get(key);
	}

	/**
	 * cell级别组成的keys
	 * 
	 * @param map
	 * @return
	 */
	public String getCellKeys(Map<String, String> map) {
		return map.get("EQU_MME_ID") + "-" + map.get("ENB_ID") + "-" + map.get("CELL_ID");
	}

	/**
	 * cell级别组成的keys
	 * 
	 * @param map
	 * @return
	 */
	public String getLocalCellKeys(Map<String, String> map) {
		return map.get("EQU_MME_ID") + "-" + map.get("ENB_ID") + "-" + map.get("LOCALCELLID");
	}

	/**
	 * enodB级别组成的keys
	 * 
	 * @param map
	 * @return
	 */
	public String getEnodbKeys(Map<String, String> map) {
		return map.get("EQU_MME_ID") + "-" + map.get("ENB_ID");
	}

	/**
	 * @param map
	 * @param groupByKey
	 * @return
	 */
	public String getMyKey(Map<String, String> map, String[] keys) {
		if (keys == null || keys.length == 0)
			return null;
		// 网元关联
		StringBuilder sb = new StringBuilder();
		for (String key : keys) {
			sb.append(map.get(key)).append("-");
		}
		return sb.toString().substring(0, sb.length() - 1);
	}

	/**
	 * 验证网元配置项
	 * 
	 * @param str
	 * @return true or false
	 */
	public boolean validateNeConfig() {
		if (!validataMmeids())
			return false;
		if (!validataRelationShipKeysGroup())
			return false;
		return true;
	}

	/**
	 * 验证MMEID配置
	 * 
	 * @return true or false
	 */
	public boolean validataMmeids() {
		if (StringUtil.isEmpty(mmeIds)) {
			LOGGER.debug("【config.ini文件中mmeIds配置项为空，请确定是否需要加载网元】");
			return false;
		}
		if (!mmeIds.startsWith("{") || !mmeIds.endsWith("}")) {
			LOGGER.debug("【config.ini文件中mmeIds配置有误，缺少'{'或'}'，或者拼写不正确，请检查】");
			return false;
		}
		mmeIds = mmeIds.replace("{", "").replace("}", "");
		String[] mmeIdsArray = StringUtil.split(mmeIds, ",");
		if (mmeIdsArray == null || mmeIdsArray.length == 0) {
			LOGGER.debug("【config.ini文件中mmeIds配置为空，请确定是否需要加载网元】");
			return false;
		}
		boolean errorFlag = false;
		for (int n = 0; n < mmeIdsArray.length; n++) {
			String mmeIdStr = mmeIdsArray[n];
			if (!StringUtil.isNum(mmeIdStr)) {
				errorFlag = true;
				LOGGER.debug("【config.ini文件中mmeIds配置有误，" + mmeIdStr + "不是数字，请检查】");
			}
		}
		if (errorFlag)
			return false;
		return true;
	}

	/**
	 * 验证MMEID配置
	 * 
	 * @return true or false
	 */
	public boolean validataRelationShipKeysGroup() {
		if (StringUtil.isEmpty(relationShipKeysGroup)) {
			LOGGER.debug("【config.ini文件中relationShipKeysGroup配置为空，没有指定关联字段组合】");
			return false;
		}
		if (!relationShipKeysGroup.startsWith("{") || !relationShipKeysGroup.endsWith("}")) {
			LOGGER.debug("【config.ini文件中relationShipKeysGroup配置有误，缺少'{'或'}'，或者拼写不正确，请检查】");
			return false;
		}
		relationShipKeysGroup = relationShipKeysGroup.replace("{", "").replace("}", "");
		String[] levelGroup = StringUtil.split(relationShipKeysGroup, ";");
		if (levelGroup == null || levelGroup.length == 0) {
			LOGGER.debug("【config.ini文件中relationShipKeysGroup配置为空，没有指定关联字段组合】");
			return false;
		}
		for (String str : levelGroup) {
			String[] array = StringUtil.split(str, ":");
			if (array == null || array.length != 2) {
				LOGGER.debug("【config.ini文件中relationShipKeysGroup配置有误，错误位于" + str + "处】");
				return false;
			}
			if (!array[1].startsWith("[") || !array[1].endsWith("]")) {
				LOGGER.debug("【config.ini文件中relationShipKeysGroup配置有误，缺少'['或']'，或者拼写不正确，请检查】");
				return false;
			}
			String keyGroups = array[1].replace("[", "").replace("]", "");
			String[] keyGroupsArray = StringUtil.split(keyGroups, "||");
			if (keyGroupsArray == null || keyGroupsArray.length == 0) {
				LOGGER.debug("【config.ini文件中relationShipKeysGroup配置有误，错误位于" + keyGroups + "处】");
				return false;
			}
			for (String string : keyGroupsArray) {
				String[] keysArray = StringUtil.split(string, ",");
				if (array[0].equalsIgnoreCase("ENODB")) {
					if (enodbKeysList == null)
						enodbKeysList = new ArrayList<String[]>(keyGroupsArray.length);
					enodbKeysList.add(keysArray);
					continue;
				}
				if (array[0].equalsIgnoreCase("CELL")) {
					if (cellKeysList == null)
						cellKeysList = new ArrayList<String[]>(keyGroupsArray.length);
					cellKeysList.add(keysArray);
					continue;
				}
			}
		}
		return true;
	}

	/**
	 * @return size
	 */
	public int getSize() {
		return NECACHER.size();
	}

	/**
	 * @return true or false
	 */
	public boolean isEmpty() {
		return NECACHER.size() == 0 ? true : false;
	}

	/**
	 * @param mmeIds
	 */
	public void setMmeIds(String mmeIds) {
		this.mmeIds = mmeIds;
	}

	/**
	 * @param relationShipKeysGroup
	 */
	public void setRelationShipKeysGroup(String relationShipKeysGroup) {
		this.relationShipKeysGroup = relationShipKeysGroup;
	}

	/**
	 * @param dataSource
	 */
	public void setDataSource(BasicDataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * @param sqlForGetCellRecords
	 */
	public void setSqlForGetCellRecords(String sqlForGetCellRecords) {
		this.sqlForGetCellRecords = sqlForGetCellRecords;
	}

	/**
	 * @param sqlForGetEnbRecords
	 */
	public void setSqlForGetEnbRecords(String sqlForGetEnbRecords) {
		this.sqlForGetEnbRecords = sqlForGetEnbRecords;
	}

}