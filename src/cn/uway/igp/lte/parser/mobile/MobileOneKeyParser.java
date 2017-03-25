package cn.uway.igp.lte.parser.mobile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.commons.dbcp.BasicDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.context.AppContext;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.util.DbUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * 终端问题一键收集
 * 
 * @author yuy 2014-09-25
 */
public class MobileOneKeyParser extends FileParser {

	/** 日志记录器 */
	private static Logger LOGGER = LoggerFactory.getLogger(MobileOneKeyParser.class);

	/** 解析器名称 */
	private static String myName = "终端一键";

	/** 记录Map */
	public List<ParseOutRecord> resultsMapList = null;

	/** 临时记录Map */
	public Map<String, String> tmpMap = null;

	/** IMSI */
	public String imsi = null;

	/** 上报时间 */
	public String reportingTime = null;

	/** callinfo/pinginfo/ftpupinfo/ftpdowninfo */
	public Map<String, String> infosMap = null;

	/** 数据库连接池 */
	public BasicDataSource connPool = (BasicDataSource) AppContext.getBean("jdbcConnection", DataSource.class);

	/** c网数据关联sql */
	public String cdmaRelation_sql = "SELECT distinct NE_SYS_ID FROM NE_CELL_C WHERE SID=? AND NID=? AND CI=? AND CHINA_NAME=?";

	/** lte网数据关联sql */
	public String lteRelation_sql = "SELECT distinct NE_CELL_ID FROM NE_CELL_L WHERE TAC=? AND PCI=? AND CELL_NAME=?";

	public MobileOneKeyParser() {
	}

	public MobileOneKeyParser(String tmpfilename) {
		super(tmpfilename);
	}

	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		reader = new BufferedReader(new InputStreamReader(inputStream, "utf-8"), 16 * 1024);

		// 先把数据粗解出来
		simplyParseData();

		if (tmpMap == null) {
			return;
		}

		resultsMapList = new ArrayList<ParseOutRecord>();
		imsi = tmpMap.get("imsi");
		reportingTime = tmpMap.get("Reportingtime");
		this.currentDataTime = TimeUtil.getyyyyMMddHHmmssDate(reportingTime);

		// 根据measureType获取info信息
		getInfosMap();

		// dataType=161, MOD_MOBILE_COLLECTION
		build161Record();

		// dataType=162, MOD_ONEKEYCOLLECTION_CELL_C
		build162Record();

		// dataType=163, MOD_ONEKEYCOLLECTION_CELL_L
		build163Record();

		// dataType=164, MOD_MOBILE_COLLECTION_CALL
		build164Record();

		// dataType=165, MOD_MOBILE_COLLECTION_MEASURE
		build165Record();

		tmpMap.clear();
		tmpMap = null;
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		return resultsMapList != null && resultsMapList.size() > (int) readLineNum;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		readLineNum++;
		ParseOutRecord record = resultsMapList.get((int) readLineNum - 1);
		record.getRecord().put("IMSI", imsi);
		record.getRecord().put("OMCID", String.valueOf(task.getExtraInfo().getOmcId()));
		record.getRecord().put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		record.getRecord().put("STAMPTIME", reportingTime);
		return record;
	}

	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();

		LOGGER.debug("[{}]-" + myName + "解析，处理{}条记录", new Object[]{task.getId(), readLineNum});
	}

	/**
	 * 解析数据
	 * 
	 * @throws IOException
	 */
	public void simplyParseData() throws IOException {
		String line = null;
		tmpMap = new HashMap<String, String>();
		String startTagName = null;
		String endTagName = null;
		StringBuilder str = null;
		while ((line = reader.readLine()) != null) {
			line = line.trim();
			if (line.indexOf("<?xml") > -1)
				continue;
			if (line.indexOf("measureobject") > -1)
				continue;

			// 根据结束标签来判断
			if (endTagName != null) {
				int pos = line.indexOf(endTagName);
				if (pos > -1) {
					str.append(line.substring(0, pos));
					tmpMap.put(startTagName, str.toString());
					endTagName = null;
				} else {
					str.append(line);
				}
				continue;
			}

			int begin = line.indexOf("<");
			int last = line.indexOf(">");
			if (begin > -1 && begin < last) {
				startTagName = line.substring(begin + 1, last);
				// 需要解析的标签
				if (NeedParsedTags.tagsSet.contains(startTagName)) {
					endTagName = "</" + startTagName + ">";
					int last_ = line.indexOf(endTagName);
					if (last_ > -1) {
						tmpMap.put(startTagName, line.substring(last + 1, last_));
						endTagName = null;
						continue;
					} else {
						str = new StringBuilder();
						str.append(line.substring(last + 1));
					}
				}
			}
		}
	}

	/**
	 * 根据measureType获取info信息
	 */
	public void getInfosMap() {
		infosMap = new HashMap<String, String>(4);
		String measureType = tmpMap.get("measureType");
		if (measureType == null)
			return;
		String[] typeArray = StringUtil.split(measureType, ",");
		for (int n = 0; typeArray != null && n < typeArray.length; n++) {
			// 1表示测试，0表示未测试
			switch (n) {
				case 0 : {
					if ("1".equals(typeArray[n])) {
						infosMap.put("callinfo", tmpMap.get("callinfo"));
					}
					break;
				}
				case 1 : {
					if ("1".equals(typeArray[n])) {
						infosMap.put("pinginfo", tmpMap.get("pinginfo"));
					}
					break;
				}
				case 2 : {
					if ("1".equals(typeArray[n])) {
						infosMap.put("ftpupinfo", tmpMap.get("ftpupinfo"));
					}
					break;
				}
				case 3 : {
					if ("1".equals(typeArray[n])) {
						infosMap.put("ftpdowninfo", tmpMap.get("ftpdowninfo"));
					}
					break;
				}
				default :
					break;
			}
		}
	}

	/**
	 * 组装dataType=161的数据
	 */
	public void build161Record() {
		ParseOutRecord record = new ParseOutRecord();
		Map<String, String> map = new HashMap<String, String>();

		String callinfo = infosMap.get("callinfo");
		String ftpupinfo = infosMap.get("ftpupinfo");
		String ftpdowninfo = infosMap.get("ftpdowninfo");

		String startSign = "拨打号码为：";
		String dialNumber = null;
		int n = 0;
		if (callinfo != null) {
			dialNumber = replace(StringUtil.getPattern(callinfo, startSign + "\\d+"), startSign);
			n = callinfo.lastIndexOf("通话成功！");
		}

		map.put("CITY_ID", tmpMap.get("cityinfo"));
		map.put("DIAL_NUMBER", dialNumber);
		map.put("CALL_NUM", callinfo == null ? "" : callinfo.substring(n - 1, n));
		map.put("FTP_UP_SIZE", ftpupinfo == null ? "" : replace(StringUtil.getPattern(ftpupinfo, "\\d+KB"), "KB"));
		map.put("FTP_UP_AVG_SPEED", ftpupinfo == null ? "" : replace(StringUtil.getPattern(ftpupinfo, "\\d+kbps"), "kbps"));
		map.put("FTP_UP_TIME", ftpupinfo == null ? "" : replace(StringUtil.getPattern(ftpupinfo, "\\d+s"), "s"));
		map.put("FTP_DOWN_SIZE", ftpdowninfo == null ? "" : replace(StringUtil.getPattern(ftpdowninfo, "\\d+KB"), "KB"));
		map.put("FTP_DOWN_AVG_SPEED", ftpdowninfo == null ? "" : replace(StringUtil.getPattern(ftpdowninfo, "\\d+kbps"), "kbps"));
		map.put("FTP_DOWN_TIME", ftpdowninfo == null ? "" : replace(StringUtil.getPattern(ftpdowninfo, "\\d+s"), "s"));

		map.put("CALLINFO", callinfo);
		map.put("PINGINFO", tmpMap.get("pinginfo"));
		map.put("FTPUPINFO", ftpupinfo);
		map.put("FTPDOWNINFO", ftpdowninfo);

		map.put("RSSI", tmpMap.get("rssi"));
		map.put("ECIO", tmpMap.get("ecio"));
		map.put("DORSSI", tmpMap.get("dorssi"));
		map.put("DOECIO", tmpMap.get("doecio"));
		map.put("SINR", tmpMap.get("sinr"));
		map.put("RSRP", tmpMap.get("rsrp"));
		map.put("RSRQ", tmpMap.get("rsrq"));
		map.put("LTESINR", tmpMap.get("ltesinr"));
		map.put("XAXIS", tmpMap.get("xAxis"));
		map.put("XAXISLTE", tmpMap.get("xAxislte"));
		map.put("CDMASIDNIDCI", tmpMap.get("cdmasidnidci"));
		map.put("LTETACPCI", tmpMap.get("ltetacpci"));

		record.setType(161);
		record.setRecord(map);
		resultsMapList.add(record);
	}

	/**
	 * 组装dataType=162的数据
	 */
	public void build162Record() {
		String[] cdmasidnidciArray = StringUtil.split(tmpMap.get("cdmasidnidci"), "|");
		String[] CDMAmainservicecellsArray = StringUtil.split(clearCellsString(tmpMap.get("CDMAmainservicecells")), ",");
		for (int m = 0; cdmasidnidciArray != null && cdmasidnidciArray.length > m && CDMAmainservicecellsArray != null
				&& CDMAmainservicecellsArray.length > m; m++) {
			String[] cellsArray = StringUtil.split(cdmasidnidciArray[m], ",");
			String cellName = CDMAmainservicecellsArray[m];
			ParseOutRecord record = new ParseOutRecord();
			Map<String, String> map = new HashMap<String, String>();
			map.put("SID", cellsArray[0]);
			map.put("NID", cellsArray[1]);
			map.put("CI", cellsArray[2]);
			// 通过SID，NID，CI和cellName关联
			map.put("NE_CELL_ID", selectForCdmaCellInfo(map, cellName));
			record.setType(162);
			record.setRecord(map);
			resultsMapList.add(record);
		}
	}

	/**
	 * 组装dataType=163的数据
	 */
	public void build163Record() {
		String[] ltetacpciArray = StringUtil.split(tmpMap.get("ltetacpci"), "|");
		String[] LTEmainservicecellsArray = StringUtil.split(clearCellsString(tmpMap.get("LTEmainservicecells")), ",");
		for (int m = 0; ltetacpciArray != null && ltetacpciArray.length > m && LTEmainservicecellsArray != null
				&& LTEmainservicecellsArray.length > m; m++) {
			String[] cellsArray = StringUtil.split(ltetacpciArray[m], ",");
			String cellName = LTEmainservicecellsArray[m];
			ParseOutRecord record = new ParseOutRecord();
			Map<String, String> map = new HashMap<String, String>();
			map.put("TAC", cellsArray[0]);
			map.put("PCI", cellsArray[1]);
			// 通过TAC，PCI和cellName关联
			map.put("NE_CELL_ID", selectForLteCellInfo(map, cellName));
			record.setType(163);
			record.setRecord(map);
			resultsMapList.add(record);
		}
	}

	/**
	 * 组装dataType=164的数据
	 */
	public void build164Record() {
		if (infosMap.get("callinfo") == null)
			return;
		String[] callinfoArray = StringUtil.split(infosMap.get("callinfo"), "！<br>");
		for (String callinfo : callinfoArray) {
			ParseOutRecord record = new ParseOutRecord();
			Map<String, String> map = new HashMap<String, String>();
			String longitude = replace(StringUtil.getPattern(callinfo, "经度：\\d+[.]\\d+"), "经度：");
			if (StringUtil.isEmpty(longitude))
				longitude = replace(StringUtil.getPattern(callinfo, "经度：\\d+"), "经度：");
			String latitude = replace(StringUtil.getPattern(callinfo, "纬度：\\d+[.]\\d+"), "纬度：");
			if (StringUtil.isEmpty(latitude))
				latitude = replace(StringUtil.getPattern(callinfo, "纬度：\\d+"), "纬度：");
			map.put("LONGITUDE", longitude);
			map.put("LATITUDE", latitude);
			map.put("CALL_NUM", replace(StringUtil.getPattern(callinfo, "第\\d+"), "第"));
			map.put("IS_SUCCESS", callinfo.indexOf("成功") > -1 ? "1" : "0");
			record.setType(164);
			record.setRecord(map);
			resultsMapList.add(record);
		}
	}

	/**
	 * 组装dataType=165的数据
	 */
	public void build165Record() {
		String[] rssiArray = StringUtil.split(tmpMap.get("rssi"), ",");
		String[] ecioArray = StringUtil.split(tmpMap.get("ecio"), ",");
		String[] dorssiArray = StringUtil.split(tmpMap.get("dorssi"), ",");
		String[] doecioArray = StringUtil.split(tmpMap.get("doecio"), ",");
		String[] sinrArray = StringUtil.split(tmpMap.get("sinr"), ",");
		String[] rsrpArray = StringUtil.split(tmpMap.get("rsrp"), ",");
		String[] rsrqArray = StringUtil.split(tmpMap.get("rsrq"), ",");
		String[] ltesinrArray = StringUtil.split(tmpMap.get("ltesinr"), ",");
		String[] xAxisArray = StringUtil.split(tmpMap.get("xAxis"), ",");
		String[] xAxislteArray = StringUtil.split(tmpMap.get("xAxislte"), ",");
		for (int m = 0; m < rssiArray.length; m++) {
			ParseOutRecord record = new ParseOutRecord();
			Map<String, String> map = new HashMap<String, String>();
			try {
				map.put("RSSI", rssiArray[m]);
				map.put("ECIO", ecioArray[m]);
				map.put("DO_RSSI", dorssiArray[m]);
				map.put("DO_ECIO", doecioArray[m]);
				map.put("DO_SINR", sinrArray[m]);
				map.put("LTE_RSRP", rsrpArray[m]);
				map.put("LTE_RSRQ", rsrqArray[m]);
				map.put("LTE_SINR", ltesinrArray[m]);
				map.put("CDMA_XAXIS", xAxisArray[m]);
				map.put("LTE_XAXIS", xAxislteArray[m]);
			} catch (Exception e) {
				LOGGER.error("获取数据时出错", e);
				continue;
			}
			record.setType(165);
			record.setRecord(map);
			resultsMapList.add(record);
		}
	}

	/**
	 * 关联C网网元信息
	 * 
	 * @param map
	 * @param cellName
	 * @return id
	 */
	public String selectForCdmaCellInfo(Map<String, String> map, String cellName) {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			con = this.connPool.getConnection();
			ps = con.prepareStatement(cdmaRelation_sql);
			int index = 1;
			ps.setInt(index++, Integer.parseInt(map.get("SID")));
			ps.setInt(index++, Integer.parseInt(map.get("NID")));
			ps.setInt(index++, Integer.parseInt(map.get("CI")));
			ps.setString(index++, cellName);
			rs = ps.executeQuery();
			if (rs.next())
				return String.valueOf(rs.getInt(1));
		} catch (Exception e) {
			LOGGER.warn("关联cdma网元出现异常，dataTime=" + reportingTime, e);
			return null;
		} finally {
			DbUtil.close(rs, ps, con);
		}
		return null;
	}

	/**
	 * 关联LTE网元信息
	 * 
	 * @param map
	 * @param cellName
	 * @return id
	 */
	public String selectForLteCellInfo(Map<String, String> map, String cellName) {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			con = this.connPool.getConnection();
			ps = con.prepareStatement(lteRelation_sql);
			int index = 1;
			ps.setInt(index++, Integer.parseInt(map.get("TAC")));
			ps.setInt(index++, Integer.parseInt(map.get("PCI")));
			ps.setString(index++, cellName);
			rs = ps.executeQuery();
			if (rs.next())
				return String.valueOf(rs.getInt(1));
		} catch (Exception e) {
			LOGGER.warn("关联lte网元出现异常，dataTime=" + reportingTime, e);
			return null;
		} finally {
			DbUtil.close(rs, ps, con);
		}
		return null;
	}

	/**
	 * @param cellsString
	 * @return
	 */
	public String clearCellsString(String cellsString) {
		return cellsString.replace("主服务小区:", "").replace("：", "").replace("<br>", "").replace("\n", "").replace("\r", "");
	}

	/**
	 * @param str
	 * @param reg
	 * @return
	 */
	public String replace(String str, String reg) {
		return str == null ? "" : str.replace(reg, "");
	}

	public static void main(String[] args) {
		String dialNumber = StringUtil.getPattern("通话经度：116.383998<br>通话经度：112.383998<br>", "经度：\\d+[.]\\d+");
		System.out.println(dialNumber);
	}
}
