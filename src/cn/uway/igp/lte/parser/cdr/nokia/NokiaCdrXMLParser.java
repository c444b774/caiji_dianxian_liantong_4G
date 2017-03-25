package cn.uway.igp.lte.parser.cdr.nokia;

import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.accessor.StreamAccessOutObject;
import cn.uway.framework.context.Vendor;
import cn.uway.framework.orientation.GridOrientation;
import cn.uway.framework.orientation.OrientationAPI;
import cn.uway.framework.orientation.Type.LONG_LAT;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.CSVCfcTempletParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.igp.lte.extraDataCache.cache.CityInfo;
import cn.uway.igp.lte.extraDataCache.cache.CityInfoCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgInfo;
import cn.uway.igp.lte.parser.cdt.CoreCommonDataManager;
import cn.uway.util.StringUtil;

/**
 * 诺西cdr解析类
 * 
 * @author sunt @2015-06-10
 *
 */
public class NokiaCdrXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(NokiaCdrXMLParser.class);

	protected StreamAccessOutObject streamOut;
	
	// 解析的文件
	protected String rawFilePath = null;

	// 文件名
	protected String rawFileName = null;
	
	// 文件名中的日期字符串对应的格式
	protected static final String FILE_NAME_FORMAT = "yyyyMMddHHmmss";
	// 匹配文件名中的日期字符串
	protected static final String FILE_NAME_PATTERN = "\\d{14}";
	// 文件名匹配通配符*，在查找field列表时使用
	protected static final String wildCard = "*";
	// 核心网与无线网关联缓存管理
	protected CoreCommonDataManager coreCommonDataManager;
	
	// 保留小数点后5位
	public DecimalFormat NUMBER_FORMAT = new DecimalFormat(".00000");

	/**
	 * xml流
	 */
	protected XMLStreamReader reader = null;
	// 是否正在读正文
	protected Boolean isReaderBody = false;
	/**
	 * XMLStreamReader无法获取上下级关系，tc来辅助
	 */
	private TagChain tc;

	/**
	 * 存放一条数据
	 */
	public Map<String, String> recordDataMap = null;

	public NokiaCdrXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	public NokiaCdrXMLParser() {
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		if (accessOutObject == null)
			throw new IOException("接入对象无效，null.");
		this.streamOut = (StreamAccessOutObject) accessOutObject;
		this.task = this.streamOut.getTask();
		this.rawFilePath = this.streamOut.getRawAccessName();
		this.rawFileName = FilenameUtils.getBaseName(rawFilePath);
		this.startTime = new Timestamp(System.currentTimeMillis()); // 开始解析的时间。
		LOGGER.debug("开始解码:{}", this.rawFilePath);
		this.parseFileName();
		// 解析模板 获取当前文件对应的templet
		this.parseTemplet();
		// 转换为缓冲流读取
		if (this.rawFilePath.endsWith(".zip")) {
			ZipInputStream zipInput = new ZipInputStream(this.streamOut.getOutObject());
			if (zipInput.getNextEntry() != null)
				this.reader = XMLInputFactory.newInstance().createXMLStreamReader(new InputStreamReader(zipInput, "GBK"));
			else {
				LOGGER.warn("压缩包中无文件，文件解析异常。文件：{}", this.rawFilePath);
				return;
			}
		} else if (accessOutObject.getRawAccessName().endsWith(".gz")) {
			this.reader = XMLInputFactory.newInstance().createXMLStreamReader(new InputStreamReader(new GZIPInputStream(this.streamOut.getOutObject()), "UTF-8"));
		} else {
			this.reader = XMLInputFactory.newInstance().createXMLStreamReader(new InputStreamReader(this.streamOut.getOutObject(), "UTF-8"));
		}

		// 核心网关联-石刚提供,等完善后开启
//		if (this.coreCommonDataManager == null) {
//			coreCommonDataManager = AppContext.getBean(
//					"lteCoreCommonDataReadConfig", CoreCommonDataManager.class);
//			coreCommonDataManager.init();
//		}
//
//		if (this.currentDataTime != null) {
//			if (!coreCommonDataManager.isCacheReady(this.currentDataTime)) {
//				LOGGER.debug(
//						"核心网话单未达到关联时间限制, 将在下一个周期继续采集 cdr时间:{}，核心网cache最大生成时间{}.",
//						this.currentDataTime, coreCommonDataManager.getMaxCacheDateTime());
//				throw new Exception("核心网话单未达到关联时间限制, 将在下一个周期继续采集.");
//			}
//		}

		tc = new TagChain();
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		tc.clear();
		int type = -1;
		this.recordDataMap = new HashMap<String, String>();
		String tagName = null;
		try {
			while (reader.hasNext()) {
				type = reader.next();

				// 只取开始和结束标签
				if (type == XMLStreamConstants.START_ELEMENT
						|| type == XMLStreamConstants.END_ELEMENT) {
					tagName = reader.getLocalName().toUpperCase();
				} else {
					tagName = null;
					continue;
				}
				if (type == XMLStreamConstants.START_ELEMENT) {
					analyseStartElement(tagName);
				} else if (type == XMLStreamConstants.END_ELEMENT) {
					if (analyseEndElement(tagName)) {
						return true;
					}
				}
			}
		} catch (XMLStreamException e) {
			LOGGER.debug("解析失败-tagName：{}-rawFileName:{}-异常信息：{}", new Object[] {
					tagName, rawFileName, e.getMessage() });
			throw e;
		}
		return false;
	}

	/**
	 * 分析结束节点。如果是根节点就返回true；如果是独立节点就从tagChain中删除对应的节点。
	 * 
	 * @param tagName
	 * @return
	 */
	private Boolean analyseEndElement(String tagName) {
		if (tagName.equals("TRACERECSESSION")) {
			return true;
		} else {
			tc.remove(tagName);
			return false;
		}
	}

	/**
	 * 分析起始标签
	 * 
	 * @param tagName
	 * @throws XMLStreamException
	 */
	private void analyseStartElement(String tagName) throws XMLStreamException {
		if (tagName.equals("TRACERECSESSION")) {
			int attrCount = reader.getAttributeCount();
			for (int i = 0; i < attrCount; i++) {
				this.recordDataMap.put(reader.getAttributeLocalName(i)
						.toUpperCase(), reader.getAttributeValue(i));
			}
			isReaderBody = true;
		} else if (isReaderBody) {
			try {
				this.recordDataMap.put(tc.getChainStr() + tagName,
						reader.getElementText());
			} catch (XMLStreamException e) {
				tc.add(tagName);
				analyseStartElement(reader.getLocalName().toUpperCase());
			}
		}
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		List<Field> fieldList = this.templet.getFieldList();
		// FIXME 返回的是ArrayMap，但是一旦put，就变成HashMap了
		Map<String, String> recordData = this
				.createExportPropertyMap(this.templet.getDataType());
		for (Field field : fieldList) {
			if (field == null) {
				continue;
			}
			String value = this.recordDataMap.get(field.getName());
			// 找不到，设置为空
			if (value == null) {
				continue;
			}
			// 是否拆封字段
			if (("true".equals(field.getIsSplit()))
					&& (!StringUtil.isBlank(field.getRegex()))) {
				recordData.put(field.getIndex(),
						StringUtil.getPatternEX(value, field.getRegex()));
			} else {
				recordData.put(field.getIndex(), value);
			}
		}
		
		// NE_CELL_L表关联获取网元信息。关联字段：VENDOR - ENB_ID - AC_CELL_ID。
		LteCellCfgInfo lteCellCfgInfo = LteCellCfgCache
				.findNeCellByVendorEnbCell(Vendor.VENDOR_NOKIA,
						recordData.get("ENB_ID"), recordData.get("AC_CELL_ID"));
		if (lteCellCfgInfo != null) {
			recordData.put("NE_ENB_ID", String.valueOf(lteCellCfgInfo.neEnbId));
			recordData.put("NE_CELL_ID", String.valueOf(lteCellCfgInfo.neCellId));
			recordData.put("COUNTY_ID", String.valueOf(lteCellCfgInfo.countyId));
			recordData.put("COUNTY_NAME", lteCellCfgInfo.countyName);
			recordData.put("CITY_ID", String.valueOf(lteCellCfgInfo.cityId));
			recordData.put("CITY_NAME", lteCellCfgInfo.cityName);
			recordData.put("FDDTDDIND", lteCellCfgInfo.fddTddInd);
		}else{
			LOGGER.debug("NE_CELL_L表关联失败-VENDOR：{}-ENB_ID:{}-AC_CELL_ID：{}", new Object[] {
					Vendor.VENDOR_NOKIA, recordData.get("ENB_ID"), recordData.get("AC_CELL_ID") });
		}

		// 接入定位 没有CONNECTION_TA（连接时间提前量）字段 无法回填
		String connection_ta = recordData.get("CONNECTION_TA");
		if (lteCellCfgInfo != null && lteCellCfgInfo.direct_angle != null && StringUtil.isNotEmpty(connection_ta)) {
			LONG_LAT accessLocation = new LONG_LAT();
			OrientationAPI.LteTaOrientation(Long.parseLong(connection_ta), lteCellCfgInfo.direct_angle, lteCellCfgInfo.longitude,
					lteCellCfgInfo.latitude, accessLocation);

			recordData.put("LONGITUDE", NUMBER_FORMAT.format(accessLocation.LON));
			recordData.put("LATITUDE", NUMBER_FORMAT.format(accessLocation.LAT));

			// 填充栅格信息
			String[] gridInfo = computGridInfo(accessLocation, lteCellCfgInfo.cityId);
			recordData.put("GRID_M", gridInfo[0]);
			recordData.put("GRID_N", gridInfo[1]);
			recordData.put("GRID_ID", gridInfo[2]);
		}

		// IMSI MSISDN关联 
		// 待关联算法出炉，石刚的hw关联需要好多字段

		ParseOutRecord record = new ParseOutRecord();
		record.setType(templet.getDataType());
		record.setRecord(recordData);
		this.recordDataMap = null;
		readLineNum++;
		return record;
	}
	
	/**
	 * 通过经度、维度信息计算栅格
	 * 复制自HwCdrGzCsvParser.java
	 * @param outLL
	 * @return 栅格信息
	 */
	protected String[] computGridInfo(LONG_LAT outLL, int cityId) {
		if (outLL.LAT == 0.0 && outLL.LON == 0.0)
			return new String[] { "0", "0", "" };
		String[] gridInfos = new String[3];
		gridInfos[0] = "0";
		gridInfos[1] = "0";
		gridInfos[2] = "";
		CityInfo cityInfo = CityInfoCache.findCity(cityId);
		// 如果城市信息未找到 则返回默认GRID信息 M/N均为0
		if (cityInfo == null)
			return gridInfos;
		double longitude = outLL.LON;
		double latitude = outLL.LAT;
		// 确定定位出来后的经纬度在当前城市范围内
		if (outLL.LON > cityInfo.longRt) {
			longitude = cityInfo.longRt;
		} else if (outLL.LON < cityInfo.longLt) {
			longitude = cityInfo.longLt;
		}
		if (outLL.LAT > cityInfo.latLt) {
			latitude = cityInfo.latLt;
		} else if (outLL.LAT < cityInfo.latRt) {
			latitude = cityInfo.latRt;
		}
		return GridOrientation.orientationGridInfo(longitude, latitude,
				cityInfo.longLt, cityInfo.longRt, cityInfo.latLt,
				cityInfo.latRt, cityInfo.getGridM(), cityInfo.getGridN());
	}

	/**
	 * 解析文件名
	 * 
	 * @throws Exception
	 */
	public void parseFileName() {
		try {
			String patternTime = StringUtil.getPattern(rawFileName,
					FILE_NAME_PATTERN);
			this.currentDataTime = getDateTime(patternTime, FILE_NAME_FORMAT);
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	/**
	 * 将时间字符串转换成format格式的Date
	 * 
	 * @param date
	 * @param format
	 * @return
	 */
	private final Date getDateTime(String date, String format) {
		if (date == null) {
			return null;
		}
		try {
			DateFormat df = new SimpleDateFormat(format);
			return df.parse(date);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	@Override
	public void parseTemplet() throws Exception {
		// 解析模板
		TempletParser csvTempletParser = new CSVCfcTempletParser();
		csvTempletParser.tempfilepath = templates;
		csvTempletParser.parseTemp();
		templetMap = csvTempletParser.getTemplets();

		getMyTemplet();

		if (templet == null) {
			throw new NullPointerException("没有找到对应的模板，解码退出");
		}
	}

	/**
	 * 获取当前文件对应的Templet
	 */
	private void getMyTemplet() {
		Iterator<String> it = templetMap.keySet().iterator();
		while (it.hasNext()) {
			String file = it.next();
			if (file.indexOf(wildCard) > -1) {
				if (FilenameUtils.wildcardMatch(FilenameUtils.getName(rawFileName),
						file)) {
					templet = templetMap.get(file);
					break;
				}
			} else {
				templet = templetMap.get(file);
			}

		}
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("[{}]-诺西cdrXML解析，处理{}条记录", new Object[] { task.getId(),
				readLineNum });
	}
}
