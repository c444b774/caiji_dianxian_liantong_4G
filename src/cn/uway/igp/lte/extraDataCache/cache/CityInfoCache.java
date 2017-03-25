package cn.uway.igp.lte.extraDataCache.cache;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.commons.dbcp.BasicDataSource;

import cn.uway.framework.connection.DatabaseConnectionInfo;
import cn.uway.framework.connection.pool.database.DbPoolManager;
import cn.uway.framework.context.AppContext;
import cn.uway.framework.orientation.GridOrientation;
import cn.uway.igp.lte.extraDataCache.cache.CMapLonLat.TJWD;
import cn.uway.util.DbUtil;

/**
 * 城市信息缓存
 * 
 * @author yuy
 * @Date 15 Apr, 2014
 */
public class CityInfoCache extends Cache {

	private static Map<Integer, CityInfo> cityMap = new HashMap<Integer, CityInfo>();

	private static Thread loadThread;

	private static Timer timer;
	
	private static DatabaseConnectionInfo connInfo;

	@Override
	public void run() {
		LOGGER.debug("城市信息加载线程启动。");
		timer = new Timer("city_info_loader");
		timer.schedule(new ReloadTimerTask(), period, period);
	}

	class ReloadTimerTask extends TimerTask {

		public void run() {
			load();
		}
	}

	/**
	 * 加载一次，然后开启定时线程
	 */
	public synchronized static void startLoad() {
		load();
		synchronized (CityInfoCache.class) {
			if (loadThread == null) {
				CityInfoCache instance = new CityInfoCache();
				loadThread = new Thread(instance, "city定时加载线程");
				loadThread.start();
			}
		}
	}

	/**
	 * 从FTP服务器城市数据文件获取城市信息
	 */
	public synchronized static void load() {
		LOGGER.debug("开始加载城市信息!");
		String sql = "SELECT CITY_ID, ENNAME, NVL(GRIDSTARTID, 0) GRIDSTARTID, NVL(LONGITUDE_LB, 0) LONGITUDE_LB, "
				+ "NVL(LONGITUDE_RB, 0) LONGITUDE_RB, NVL(LATITUDE_LB, 0) LATITUDE_LB, NVL(LATITUDE_RB, 0) LATITUDE_RB, "
				+ "NVL(sid, 0) SID, NVL(LNG_LT, 0) LNG_LT, NVL(LNG_RB, 0) LNG_RB, NVL(LAT_LT, 0) LAT_LT, NVL(LAT_RB, 0) LAT_RB "
				+ "FROM CFG_CITY WHERE LAT_RB IS NOT NULL ORDER BY CITY_ID";
		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try {
			if(connInfo == null)
			{
				getConnInfo();
			}
			LOGGER.debug("查询城市信息的SQL为：{}", sql);
			conn = DbPoolManager.getConnection(connInfo);
			pstmt = conn.prepareStatement(sql);
			rs = pstmt.executeQuery();

			CityInfo info = null;
			Map<Integer, CityInfo> tmp = new HashMap<Integer, CityInfo>();

			while (rs.next()) {
				info = new CityInfo(rs.getInt("CITY_ID"), rs.getString("ENNAME"), rs.getInt("GRIDSTARTID"), rs.getDouble("LONGITUDE_LB"),
						rs.getDouble("LONGITUDE_RB"), rs.getDouble("LATITUDE_LB"), rs.getDouble("LATITUDE_RB"), rs.getInt("SID"),
						rs.getDouble("LNG_LT"), rs.getDouble("LNG_RB"), rs.getDouble("LAT_LT"), rs.getDouble("LAT_RB"));

				CMapLonLat cm = new CMapLonLat();
				TJWD jwdCenter = cm.new TJWD(info.getMinLon(), info.getMinLat());
				TJWD jwdTmp = cm.new TJWD(info.getMaxLon(), info.getMinLat());
				jwdTmp.SetPoint(info.getMinLon(), info.getMaxLat());
				double[] out = new double[1];

				// 计算城市的长度和宽度
				info.setWidth((int) cm.distance(jwdCenter, jwdTmp, out));
				info.setHeigh((int) cm.distance(jwdTmp, jwdCenter, out));

				// 城市max Grid_m。栅格从0开始计位
				info.setGridM(GridOrientation.distanceLongitude(info.longLt, info.longRt, info.latRt, GridOrientation.GRID_LEN) - 1);
				// 城市max Grid_n。栅格从0开始计位
				info.setGridN(GridOrientation.distanceLatitude(info.latRt, info.latLt, GridOrientation.GRID_LEN) - 1);

				// 每个格子所占的经纬度
				info.setLon_100((info.getMaxLon() - info.getMinLon()) / info.getGridN());
				info.setLat_100((info.getMaxLat() - info.getMinLat()) / info.getGridM());

				tmp.put(info.getCityId(), info);
			}
			// 增加判断 如果新记录条数少于原来记录*factor 则不替换原来的数据
			int num = cityMap.size();
			int newNum = tmp.size();
			if (!tmp.isEmpty() && (num == 0 || num * factor > newNum)) {
				cityMap.clear();
				cityMap.putAll(tmp);
				LOGGER.debug("城市信息加载成功。");
			}
		} catch (Exception e) {
			LOGGER.error("城市信息加载失败:", e);
		} finally {
			DbUtil.close(rs, pstmt, conn);
		}
	}
	
	/**
	 * 获取城市所在库的信息
	 */
	private synchronized static void getConnInfo()
	{
		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			String sqlForLtePrimaryDB = AppContext.getBean("sqlForLtePrimaryDB", String.class);
			LOGGER.debug("城市所在数据库的信息SQL为：{}", sqlForLtePrimaryDB);
			conn = AppContext.getBean("datasource", BasicDataSource.class).getConnection();
			pstmt = conn.prepareStatement(sqlForLtePrimaryDB);
			rs = pstmt.executeQuery();
			if(rs.next()) {
				connInfo = new DatabaseConnectionInfo();
				connInfo.setUserName(rs.getString("USER_NAME"));
				connInfo.setPassword(rs.getString("USER_PWD"));
				connInfo.setDriver(rs.getString("DRIVER"));
				connInfo.setUrl(rs.getString("URL"));
				connInfo.setMaxActive(rs.getInt("MAX_ACTIVE"));
				connInfo.setMaxWait(rs.getInt("MAX_WAIT"));
			}
		} catch (Exception e) {
			LOGGER.error("获取城市所在数据库的信息失败:", e);
		} finally {
			DbUtil.close(rs, pstmt, conn);
		}
	}

	/**
	 * 获取key对应的城市信息
	 * 
	 * @param key
	 * @return
	 */
	public static CityInfo findCity(int key) {
		if (cityMap == null) {
			return null;
		}

		synchronized (cityMap) {
			return cityMap.get(key);
		}
	}

	public static boolean isEmpty() {
		return cityMap.size() > 0 ? false : true;
	}
}