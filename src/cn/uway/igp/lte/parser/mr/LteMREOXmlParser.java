package cn.uway.igp.lte.parser.mr;

import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.context.AppContext;
import cn.uway.framework.log.BadWriter;
import cn.uway.framework.orientation.GridOrientation;
import cn.uway.framework.orientation.Type.DEV_TYPE;
import cn.uway.framework.orientation.Type.LONG_LAT;
import cn.uway.framework.orientation.Type.ONEWAYDELAY_CELL;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.task.worker.TaskWorkTerminateException;
import cn.uway.framework.warehouse.exporter.template.DbExportTemplateBean;
import cn.uway.framework.warehouse.exporter.template.ExportTemplateBean;
import cn.uway.igp.lte.extraDataCache.cache.CityInfo;
import cn.uway.igp.lte.extraDataCache.cache.CityInfoCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgInfo;
import cn.uway.igp.lte.extraDataCache.cache.LteNeiCellCfgDynamicCache;
import cn.uway.igp.lte.extraDataCache.cache.LteNeiCellCfgInfo;
import cn.uway.igp.lte.service.AbsImsiQuerySession.ImsiRequestResult;
import cn.uway.igp.lte.service.ImsiQueryHelper;
import cn.uway.igp.lte.service.LteCoreCommonDataManager;
import cn.uway.igp.lte.util.LTEOrientUtil;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class LteMREOXmlParser extends FileParser {

	/**
	 * 是否关联IMSI服务，默认关闭
	 */
	protected static final boolean JoinImsiService = false;

	protected static final String myName = "MRE/MRO解析(XML)";

	protected static final int MRE_MRO_BEGIN_DATA_TYPE = 2000;

	protected static final String neFieldSplitChar = ";";

	protected int nMinOrientNeiCellsNumber = 0;

	private static final Logger LOGGER = LoggerFactory.getLogger(LteMREOXmlParser.class);

	protected static final Logger badWriter = BadWriter.getInstance().getBadWriter();

	/** 根据文件名获得，TDD数据为true， FDD数据为false, 不确定为null */
	protected Boolean isTDDFile;

	/** 是否是MRE文件, true=MRE文件、 false=MRO文件、 null=不确定的文件 */
	protected Boolean isMREFile;

	/**
	 * 数据类型
	 */
	protected int currFileDataType = -1;

	/** eNB id */
	protected String ENB_ID;

	/** 当前记录时间戳 */
	protected Date currRecordTimeStamp;

	/** 子记录在每一个<Object>标签下的行序号 */
	protected int objectSubID = 0;

	/** 看同一个Object标签下面有几条记录，第一条为1、第二第为2、... */
	protected int objectSubIndex;

	/** 版本 */
	protected String version;

	/** 输入流(ZIP), 当输入流是ZIP文件格式时，该流有效 */
	protected ZipInputStream zipstream = null;

	/** ZIP包中的子文件，和ZIP流同时存在 */
	protected ZipEntry entry = null;

	/** 当前解码的文件名 */
	protected String entryFileName = null;

	/** 输入流 */
	protected InputStream rawFileStream;

	/** XML流 */
	protected XMLStreamReader reader = null;

	/** 邻区MR记录列表 */
	List<Map<String, String>> lstSubNeMrRecord = new ArrayList<Map<String, String>>(256);

	/** 数据记录map */
	protected Map<String, String> vLableMrDataRecordMap = null;

	/** 数据记录map,一条mr原始数据解析后，会根据邻区数据生成多条记录(1+邻区数);CLT_MR_MRO_DETAIL_L */
	private List<Map<String, String>> mrCellDataRecordList = new ArrayList<>(10);

	/** <Object>标签的公共属性 */
	protected Map<String, String> objLabelAttrValueMap = null;
	
	/** 字段列表 **/
	protected String[] columns = null;

	/** 输出到数据库的表名 */
	protected String exportTableName;

	/**
	 * 定位时匹配大于2个邻区的记录数
	 */
	protected int moreThan2NeiCellsRecordNum = 0;

	/**
	 * 成功配匹到网元的记录数
	 */
	protected int matchNeInfoSucessRecordNum = 0;

	/**
	 * 无效网元记录数
	 */
	protected int invalidNeInfoRecordNum = 0;

	/**
	 * 无效的MR记录(数据为空)
	 */
	protected int invalidMrRecordNum = 0;

	/** 经、纬度中包含字段的模式 */
	protected Pattern alphaPtn = Pattern.compile("[a-zA-Z]");
	
	/**mr 经纬度需要保留6位小数*/
	DecimalFormat mrDf = new DecimalFormat(".000000");
	
	// 保留小数点后5位
	protected DecimalFormat df = new DecimalFormat(".00000");

	protected boolean is_valid_record = true;

	protected ImsiQueryHelper imsiQueryHelper;

	// 厂家中文件名、　厂家简称、　厂家编号、　文件名标识
	protected final static String[][] vendorTag = {{"爱立信", "ERI", "ZY0801", "ERICSSON"}, {"大唐", "DT", "ZY0802", "DATANG"},
			{"锋火", "FH", "ZY0803", "FH"}, {"中兴", "ZTE", "ZY0804", "ZTE"}, {"普天", "PT", "ZY0805", "POTEVIO"}, {"上海贝尔", "ASB", "ZY0806", "ALCATEL"},
			{"诺西", "NK", "ZY0807", "NSN"}, {"华为", "HW", "ZY0808", "HUAWEI"}};

	protected int vendorInfoByVendorTagIndex = -1;

	public LteMREOXmlParser() {
		String sMinOrientNeiCellsNumber = AppContext.getBean("minOrientNeiCellsNumber", String.class);
		if (sMinOrientNeiCellsNumber != null && org.apache.commons.lang.math.NumberUtils.isNumber(sMinOrientNeiCellsNumber)) {
			nMinOrientNeiCellsNumber = Integer.parseInt(sMinOrientNeiCellsNumber);
		}
	}

	public LteMREOXmlParser(String tmpfilename) {
		super(tmpfilename);
	}

	@SuppressWarnings("unused")
	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.before();

		this.version = null;
		this.templet = null;

		this.zipstream = null;
		this.entry = null;
		this.entryFileName = null;

		this.isTDDFile = null;
		this.isMREFile = null;
		this.currFileDataType = -1;
		this.ENB_ID = null;
		this.currRecordTimeStamp = null;
		this.objectSubID = 0;
		this.objectSubIndex = 0;
		this.rawFileStream = null;
		this.reader = null;
		this.vLableMrDataRecordMap = new HashMap<String, String>();
		this.objLabelAttrValueMap = new HashMap<String, String>();
		this.columns = null;
		this.exportTableName = null;

		LOGGER.debug("开始解码:{} nMinOrientNeiCellsNumber={}", new Object[]{accessOutObject.getRawAccessName(), nMinOrientNeiCellsNumber});

		// String rawFilePath = accessOutObject.getRawAccessName();

		// 如果是ZIP压缩包，则可能是解多个文件，所以要用ZipInputStream;
		if (accessOutObject.getRawAccessName().toLowerCase().endsWith(".zip")) {
			this.zipstream = new ZipInputStream(inputStream);
			entry = zipstream.getNextEntry();
			if (entry == null)
				return;

			this.rawFileStream = zipstream;
			LOGGER.debug("开始解析子文件:{}, ZIP文件：{}", entry.getName(), this.rawName);
			extractCommFieldByFileName(entry.getName());
		} else if (accessOutObject.getRawAccessName().toLowerCase().endsWith(".gz")) {
			this.rawFileStream = new GZIPInputStream(inputStream);
			extractCommFieldByFileName(accessOutObject.getRawAccessName());
		} else {
			this.rawFileStream = inputStream;
			extractCommFieldByFileName(accessOutObject.getRawAccessName());
		}

		if (JoinImsiService && isMREFile != null && !isMREFile && ImsiQueryHelper.isImsiServerEnable()) {
			imsiQueryHelper = ImsiQueryHelper.getHelperInstance(this.task.getId());
			Date fileTime = this.currentDataTime;
			if (fileTime != null) {
				ImsiRequestResult result = imsiQueryHelper.isCacheReady(fileTime.getTime());
				if (result == null) {
					throw new TaskWorkTerminateException("查询IMSI服务器出错");
				}

				if (result.value != ImsiRequestResult.RESPONSE_CACHE_IS_READY) {
					String errMsg = ImsiRequestResult.getResponseValueDesc(result.value);
					LOGGER.debug("{} mr时间:{}，核心网{}cache最大生成时间{}.", new Object[]{errMsg, getDateString(fileTime), result.getRequestServerInfo(),
							getDateString(new Date(result.maxServerTimeInCache))});

					throw new TaskWorkTerminateException("核心网话单未达到关联时间条件或服务器出错, 将在下一个周期继续采集.");
				}
			}
		}

		XMLInputFactory fac = XMLInputFactory.newInstance();
		fac.setProperty("javax.xml.stream.supportDTD", false);
		this.reader = fac.createXMLStreamReader(this.rawFileStream);
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		do {
			if (!mrCellDataRecordList.isEmpty()) {
				return true;
			}
			if (extractNextRecord())
				return true;

			if (prepareNextZipFileEntry())
				continue;

			break;
		} while (true);

		return false;
	}

	protected boolean prepareNextZipFileEntry() throws IOException, XMLStreamException {
		if (this.zipstream == null)
			return false;

		// 复位公共信息参数
		this.columns = null;
		this.templet = null;
		// this.currentDataTime = null;
		this.entryFileName = null;

		this.entry = zipstream.getNextEntry();
		if (entry != null) {
			// 从文件名中提取公共字段信息
			extractCommFieldByFileName(entry.getName());

			// 设置xml解析器reader
			XMLInputFactory fac = XMLInputFactory.newInstance();
			fac.setProperty("javax.xml.stream.supportDTD", false);
			this.reader = fac.createXMLStreamReader(this.rawFileStream);

			LOGGER.debug("开始解析子文件:{}, ZIP文件：{}", entry.getName(), this.rawName);

			return true;
		}

		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		/* 如果是拆分数据类型 */
		if (!mrCellDataRecordList.isEmpty()) {
			ParseOutRecord record = new ParseOutRecord();
			record.setType(MRE_MRO_BEGIN_DATA_TYPE + 2);
			record.setRecord(mrCellDataRecordList.remove(0));
			return record;
		}
		if (!is_valid_record) {
			++invalidMrRecordNum;
			return null;
		}

		readLineNum++;
		ParseOutRecord record = new ParseOutRecord();
		Map<String, String> map = this.createExportPropertyMap(this.currFileDataType);
		// 公共回填字段
		map.put("START_TIME", TimeUtil.getDateString(this.currRecordTimeStamp));
		map.put("TIME_STAMP_MSEC", String.valueOf(this.currRecordTimeStamp.getTime() % 1000));
		map.put("CITY_ID", String.valueOf(task.getExtraInfo().getCityId()));
		map.put("LTE_FDD_TDD", this.isTDDFile ? "1" : "0");
		map.put("ENB_ID", this.ENB_ID);
		map.put("RAW_FILE_KEY1", this.ENB_ID);
		// 厂家编号
		map.put("VENDOR", vendorTag[this.vendorInfoByVendorTagIndex][2]);

		// 填充Object标签的属性
		map.putAll(this.objLabelAttrValueMap);

		// 填充dataRecordMap
		map.putAll(this.vLableMrDataRecordMap);

		// XXX start 从网元中关联不到city_id的暂时丢弃,在config.ini中配置为y或n
		boolean NotAssociateCityIdDiscard = true;
		try {
			if ("n".equalsIgnoreCase(AppContext.getBean("LteMREONotAssociateCityIdDiscard", String.class))) {
				NotAssociateCityIdDiscard = false;
			}
		} catch (Exception ex) {
			LOGGER.debug("对于lte的mro,mre数据从网元中关联不到city_id的记录默认丢弃,在config.ini中只能配置LteMREONotAssociateCityIdDiscard为n才能关闭");
		}
		if (NotAssociateCityIdDiscard) {
			if (StringUtil.isEmpty(vLableMrDataRecordMap.get("CITY_ID"))) {
				try {
					StringBuilder dis_record = new StringBuilder();
					dis_record.append("{");
					for (String key : map.keySet()) {
						dis_record.append(key);
						dis_record.append("=");
						dis_record.append(map.get(key));
						dis_record.append(",");
					}
					dis_record.delete(dis_record.length() - 1, dis_record.length());
					dis_record.append("}");
					badWriter.debug("丢弃从网元中关联不到city_id的记录: {}", dis_record.toString());
					dis_record = null;
					vLableMrDataRecordMap.clear();
					return null;
				} catch (Exception e) {
					return null;
				}
			}
		}
		// XXX end
		// 用于江苏汇总将一条mro数据根据主小区、邻小区抽取成多条记录
		if (this.currFileDataType == MRE_MRO_BEGIN_DATA_TYPE + 1) {
			extractCellData(map);
		}
		// 清除记录暂存Map
		this.vLableMrDataRecordMap.clear();
		record.setType(this.currFileDataType);
		record.setRecord(map);
		return record;
	}

	/**
	 * 从mro记录中抽取出各邻区的数据
	 * 
	 * @param map
	 */
	private void extractCellData(Map<String, String> map) {
		Map<String, String> pubCol = new HashMap<>();
		pubCol.put("ID", UUID.randomUUID().toString());
		pubCol.put("START_TIME", map.get("START_TIME"));
		pubCol.put("NE_CELL_ID", map.get("NE_CELL_ID"));
		pubCol.put("MMEUES1APID", map.get("MMEUES1APID"));
		pubCol.put("MMEGROUPID", map.get("MMEGROUPID"));
		pubCol.put("MMECODE", map.get("MMECODE"));
		pubCol.put("VENDOR", map.get("VENDOR"));
		pubCol.put("GRID_ID", map.get("GRID_ID"));
		/* 各小区处理 */
		String rsrp = map.get("MR.LTESCRSRP");
		// 邻区
		String ncRsrp = map.get("MR.LTENCRSRP");
		int maxIndex = -1;
		if (ncRsrp != null) {
			String[] ncRsrpAry = ncRsrp.split(neFieldSplitChar);
			maxIndex = getMaxRSRPIndex(rsrp, ncRsrpAry);
			/*
			 * 处理邻区数据，邻小区数据取自以下字段（需对齐拆分，如果一个MRO采样中包含n个邻区，则拆分成n行录入MRO分频详单表） MR_LTENCPCI MR_LTENCEARFCN MR_LTENCRSRP MR_LTENCRSRQ
			 */
			String[] ncPci = map.get("MR.LTENCPCI").split(neFieldSplitChar);
			String[] ncEarfcn = map.get("MR.LTENCEARFCN").split(neFieldSplitChar);
			String[] ncRsrq = map.get("MR.LTENCRSRQ").split(neFieldSplitChar);
			for (int i = 0; i < ncPci.length; i++) {
				Map<String, String> cellDataMap = new HashMap<>();
				cellDataMap.put("PCI", ncPci[i]);
				cellDataMap.put("EARFCN", ncEarfcn[i]);
				cellDataMap.put("RSRP", ncRsrpAry[i]);
				cellDataMap.put("RSRQ", ncRsrq[i]);
				if (maxIndex == i && !StringUtils.isBlank(ncRsrpAry[i])) {
					/** MARK为自定义类型，当前定义：1，rsrp最大的那个值 */
					cellDataMap.put("MARK", "1");
				}
				cellDataMap.putAll(pubCol);
				mrCellDataRecordList.add(cellDataMap);
			}
		}
		/* MR_LTESCPCI MR_LTESCEARFCN MR_LTESCRSRP MR_LTESCRSRQ：对于一个MRO采样都是相同的，取一个数值即可 */
		Map<String, String> scDataMap = new HashMap<>();
		scDataMap.put("PCI", map.get("MR.LTESCPCI"));
		scDataMap.put("EARFCN", map.get("MR.LTESCEARFCN"));
		scDataMap.put("RSRP", rsrp);
		scDataMap.put("RSRQ", map.get("MR.LTESCRSRQ"));
		scDataMap.put("MAINCELL", "1");
		if (maxIndex == -1 && !StringUtils.isBlank(rsrp)) {
			scDataMap.put("MARK", "1");
		}
		scDataMap.putAll(pubCol);
		mrCellDataRecordList.add(scDataMap);
	}

	/**
	 * 获取小区、邻区中最大rsrp对应的索引
	 * 
	 * @param rsrp
	 * @param ncRsrp
	 * @return
	 */
	private int getMaxRSRPIndex(String rsrp, String[] ncRsrp) {
		int index = -1;
		float maxvalue  =0f;
		if(!StringUtils.isBlank(rsrp))
			maxvalue = Float.valueOf(rsrp);
		 
		if (null != rsrp && ncRsrp.length > 0) {
			for (int i = 0; i < ncRsrp.length; i++) {
				if ("".equals(ncRsrp[i].trim()))
					continue;
				float tem = Float.valueOf(ncRsrp[i]);
				if (tem > maxvalue) {
					maxvalue = tem;
					index = i;
				}
			}
		}
		return index;
	}

	protected ONEWAYDELAY_CELL createOnwayDelayCell(LTEOrientUtil p, LteCellCfgInfo ncInfo, Double fRSRP, Double fTA) {
		if (ncInfo == null || ncInfo.latitude == null || ncInfo.longitude == null) {
			return null;
		}

		return p.getCellInfoType(ncInfo, fRSRP, fTA, false);
	}

	/**
	 * 根据主小区、邻小区进行定位计算
	 * 
	 * @param info
	 *            主小区
	 * @param info1
	 *            邻小区
	 * @param map
	 */
	private void orientation(LTEOrientUtil p, LteCellCfgInfo ncInfo, List<ONEWAYDELAY_CELL> accessCelll, Map<String, String> map) {

		LONG_LAT outLL = p.doLocation(DEV_TYPE.LTE_MR, accessCelll.toArray(new ONEWAYDELAY_CELL[accessCelll.size()]));
		// 经、纬度回填
		map.put("LONGITUDE", df.format(outLL.LON));
		map.put("LATITUDE", df.format(outLL.LAT));
		// map.put("LONGITUDE", String.valueOf(outLL.LON));
		// map.put("LATITUDE", String.valueOf(outLL.LAT));
		// 填充栅格信息
		String[] gridInfo = computGridInfo(outLL, ncInfo.cityId);
		map.put("GRID_M", gridInfo[0]);
		map.put("GRID_N", gridInfo[1]);
		map.put("GRID_ID", gridInfo[2]);
	}

	/**
	 * 通过经度、维度信息计算栅格
	 * 
	 * @param outLL
	 * @return 栅格信息
	 */
	protected String[] computGridInfo(LONG_LAT outLL, int cityId) {
		if (outLL.LAT == 0.0 && outLL.LON == 0.0)
			return new String[]{"0", "0", ""};
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
		return GridOrientation.orientationGridInfo(longitude, latitude, cityInfo.longLt, cityInfo.longRt, cityInfo.latLt, cityInfo.latRt,
				cityInfo.getGridM(), cityInfo.getGridN());
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("[{}]-{}，成功处理{}条记录，忽略的{}条无效记录, 其中定位关联到邻区两个及上的记录数:{}，无效网元记录数:{}", new Object[]{task.getId(), myName, readLineNum,
				invalidMrRecordNum, moreThan2NeiCellsRecordNum, invalidNeInfoRecordNum});
	}

	/**
	 * 解析文件名时间 文件样例： TD-LTE_MRE_FH_OMCNB_00019F9A_20140808171500.xml
	 * 
	 * @throws Exception
	 */
	public void extractCommFieldByFileName(String fileEntryName) {
		try {
			String fileName = FileUtil.getFileName(fileEntryName);
			this.entryFileName = fileName;
			String fileUppercaseName = this.entryFileName.toUpperCase();

			// 判断是FDD还是TDD
			this.isTDDFile = null;
			if (fileUppercaseName.startsWith("TD")) {
				this.isTDDFile = true;
			} else if (fileUppercaseName.startsWith("FD")) {
				this.isTDDFile = false;
			} else {
				LOGGER.error("不能从文件名中判断是TDD还是FDD文件. fileName:{}", fileEntryName);
				return;
			}

			// 判断是是MRE还是MRO
			this.isMREFile = null;
			if (fileUppercaseName.indexOf("MRE") >= 0) {
				this.isMREFile = true;
			} else if (fileUppercaseName.indexOf("MRO") >= 0) {
				this.isMREFile = false;
			} else {
				LOGGER.error("不能从文件名中判断是MRE还是MRO文件. fileName:{}", fileEntryName);
				return;
			}

			// 确定解码文件的数据类型
			this.currFileDataType = MRE_MRO_BEGIN_DATA_TYPE;
			if (!this.isMREFile) {
				this.currFileDataType = MRE_MRO_BEGIN_DATA_TYPE + 1;
			}

			// 从文件名中判断默认厂家标识
			this.vendorInfoByVendorTagIndex = -1;
			for (int i = 0; i < vendorTag.length; ++i) {
				if (fileUppercaseName.indexOf("_" + vendorTag[i][3] + "_") > 0) {
					this.vendorInfoByVendorTagIndex = i;
					break;
				}
			}
			// 如果无法从文件名中判断厂家数据相关信息，则从任务表中查取
			String taskVendorCode = this.task.getExtraInfo().getVendor();
			if (this.vendorInfoByVendorTagIndex < 0) {
				for (int i = 0; i < vendorTag.length; ++i) {
					if (taskVendorCode.equals(vendorTag[i][2])) {
						this.vendorInfoByVendorTagIndex = i;
						break;
					}
				}
			}
			if (this.vendorInfoByVendorTagIndex < 0) {
				LOGGER.error("不能从文件名或任务表中判断文件的厂家归属. vendorCode:{}  fileName:{}", taskVendorCode, fileEntryName);
				return;
			}

			// 获取要输出到的数据表名;
			this.exportTableName = "CLT_MR_" + vendorTag[this.vendorInfoByVendorTagIndex][1] + "_" + (this.isMREFile ? "MRE" : "MRO") + "_L";

			List<ExportTemplateBean> exportBeanList = this.getCurrentJob().getExportTemplateBeans();
			List<ExportTemplateBean> validExportBeanList = new ArrayList<ExportTemplateBean>(exportBeanList.size() / 2);
			for (ExportTemplateBean bean : exportBeanList) {
				if (!(bean instanceof DbExportTemplateBean)) {
					validExportBeanList.add(bean);
					continue;
				}

				DbExportTemplateBean dbBean = (DbExportTemplateBean) bean;
				if (dbBean.getDataType() != this.currFileDataType) {
					continue;
				}

				if (!dbBean.getTable().getTableName().startsWith("CLT_MR_")) {
					validExportBeanList.add(bean);
					continue;
				}

				dbBean.getTable().setTableName(this.exportTableName);
				validExportBeanList.add(dbBean);
			}

			if (validExportBeanList.size() > 0) {
				this.getCurrentJob().setExportTemplateBeans(validExportBeanList);
			}

			// 先从文件名是截取一个默认的时间, 真正最后入库的还是从原始文件解析出来的时间.
			this.currentDataTime = parseLteMrFileDateTime(fileName);
			if (currentDataTime != null) {
				// 新建一个对象的目的，是防止currRecordTimeStamp引用currentDataTime, 变成同个对象一改都改
				this.currRecordTimeStamp = new Date(this.currentDataTime.getTime());
			}
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	/**
	 * 从当前的entry中，提取下一条记录
	 * 
	 * @return
	 * @throws XMLStreamException
	 * @throws ParseException
	 */
	protected boolean extractNextRecord() throws XMLStreamException, ParseException {
		this.lstSubNeMrRecord.clear();
		this.vLableMrDataRecordMap.clear();
		is_valid_record = true;

		// 从文件名中提取的关键信息缺失，则不解析，直接返回false;
		if (this.isTDDFile == null || this.isMREFile == null || this.vendorInfoByVendorTagIndex < 0 || this.exportTableName == null) {
			return false;
		}

		// 标签名称
		String elTagName;
		// 标签类型
		int elType = -1;

		try {
			while (reader.hasNext()) {
				elTagName = null;
				try {
					elType = reader.next();
				} catch (Exception e) {
					continue;
				}
				// 只取开始和结束标签
				if (elType != XMLStreamConstants.START_ELEMENT && elType != XMLStreamConstants.END_ELEMENT)
					continue;
				elTagName = reader.getLocalName();

				// 以下代码的解析，按在xml出现的频率，频率高的在上，频率低写在下面，所以要倒着看
				switch (elType) {
					case XMLStreamConstants.START_ELEMENT : {
						// 第一个v标签，代表一条记录
						if ("v".equalsIgnoreCase(elTagName)) {
							// date:2015-11-11 explain:对于"RIP数据是子帧级上报的RIP数据"只有一个字段的不采集
							if (this.columns == null || this.columns.length < 2)
								continue;

							String valueText = reader.getElementText();
							if (valueText == null)
								continue;

							Map<String, String> recordMap = vLableMrDataRecordMap;
							if (this.objectSubID < 1) {
								this.lstSubNeMrRecord.add(this.vLableMrDataRecordMap);
							} else {
								recordMap = new HashMap<String, String>();
								lstSubNeMrRecord.add(recordMap);
							}

							valueText = valueText.trim();
							String[] values = valueText.split(" ");
							if (values == null || values.length < 1)
								continue;

							for (int i = 0; i < values.length; ++i) {
								String value = values[i];
								if (value == null || value.trim().length() < 1 || i >= this.columns.length || value.equalsIgnoreCase("NIL"))
									continue;

								recordMap.put(this.columns[i], value);
							}

							// 对RSRP值进行转换
							{
								Double fRSRP = null;
								Double fNCRSRP = null;
								String rsrp = recordMap.get("MR.LTESCRSRP");
								if (rsrp != null && rsrp.length() > 0) {
									fRSRP = Double.parseDouble(rsrp);
									if (fRSRP != null) {
										fRSRP -= 140;
										recordMap.put("MR.LTESCRSRP", fRSRP.toString());
									}
								}

								String ncrsrp = recordMap.get("MR.LTENCRSRP");
								if (ncrsrp != null && ncrsrp.length() > 0) {
									fNCRSRP = Double.parseDouble(ncrsrp);
									if (fNCRSRP != null) {
										fNCRSRP -= 140;
										recordMap.put("MR.LTENCRSRP", fNCRSRP.toString());
									}
								}
							}

							//去掉MR_Longitude  MR_Latitude 114.203739E  后的字母
							String la = recordMap.get("MR.LATITUDE");
							String lo = recordMap.get("MR.LONGITUDE");
							//中兴mr经纬度需要转换后再入库
							if(!StringUtil.isEmpty(la)){
								la = alphaPtn.matcher(la).replaceAll("");
								//中兴mr经纬度需要转换后再入库
								if (this.vendorInfoByVendorTagIndex == 3){
									double fLa = Double.parseDouble(la);
									recordMap.put("MR.LATITUDE", mrDf.format(fLa/8388608*90));
								}else{
									recordMap.put("MR.LATITUDE", la);
								}
							}
							if(!StringUtil.isEmpty(lo)){
								lo = alphaPtn.matcher(lo).replaceAll("");
								//中兴mr经纬度需要转换后再入库
								if (this.vendorInfoByVendorTagIndex == 3){
									double fLo = Double.parseDouble(lo);
									recordMap.put("MR.LONGITUDE", mrDf.format(fLo/16777216*360));
								}else{
									recordMap.put("MR.LONGITUDE", lo);
								}
							}
							
							recordMap.put("OBJECT_SUB_ID", String.valueOf(++this.objectSubID));
							// return true;
						} else if ("object".equalsIgnoreCase(elTagName)) {
							// 每遇到"object"标签，将objectSubID置为1
							this.objectSubID = 0;
							if (this.columns == null || this.columns.length < 2)
								continue;

							/**
							 * 分解下列标签属性: V1: <object EventType="A1" MmeCode="1" MmeGroupId="32768" MmeUeS1apId="16780889"
							 * TimeStamp="2014-04-22T14:25:48.968" id="900804-0"> V2 (2016-07-11): <object MR.MmeCode="1" MR.MmeGroupId="12801"
							 * MR.MmeUeS1apId="12605376" MR.TimeStamp="2016-07-07T11:00:08.320" MR.objectId="49">
							 */
							this.objLabelAttrValueMap.clear();
							int nAttrCount = reader.getAttributeCount();
							for (int i = 0; i < nAttrCount; ++i) {
								String attrName = reader.getAttributeLocalName(i).toUpperCase();
								String attrValue = reader.getAttributeValue(i).trim();

								// 对新版本MR作一些兼容性处理，使用旧的名称
								{
									final String tagPreFix = "MR.";
									if (attrName.startsWith(tagPreFix)) {
										attrName = attrName.substring(tagPreFix.length());
									}

									if (attrName.equals("OBJECTID"))
										attrName = "ID";
								}

								if (attrValue == null || attrValue.length() < 1 || attrValue.equals("NIL")) {
									if (attrName.equalsIgnoreCase("MmeCode") || attrName.equalsIgnoreCase("MmeGroupId")
											|| attrName.equalsIgnoreCase("MmeUeS1apId")) {
										is_valid_record = false;
										break;
									}

									continue;
								}

								this.objLabelAttrValueMap.put(attrName, attrValue);
							}

							String timeStamp = this.objLabelAttrValueMap.remove("TIMESTAMP");
							this.setCurrDataTimeBytimeStamp(timeStamp);
							// 处理object id
							String objID = this.objLabelAttrValueMap.get("ID");
							if (objID != null && objID.length() > 0) {
								this.objLabelAttrValueMap.put("OBJECT_ID", objID);
								String[] objIDValues = objID.split("\\:");
								if (objIDValues.length > 0 && objIDValues[0] != null) {
									// 默认值
									this.objLabelAttrValueMap.put("CELL_NO", objIDValues[0]);

									// 对于这种数据的划分581647-48:1825:9
									int pos = objIDValues[0].indexOf('-');
									if (pos > 0) {
										String cellID = objIDValues[0].substring(pos + 1);
										this.objLabelAttrValueMap.put("CELL_NO", cellID);
									} else {
										Long nCellID = null;
										try {
											// cellID 一般都小于256, 大于的是R16C10SPC240版本需要取低8位的数值
											nCellID = Long.parseLong(objID);
											nCellID = (nCellID & 0xFF);
											this.objLabelAttrValueMap.put("CELL_NO", nCellID.toString());
										} catch (NumberFormatException e) {
										}
									}

									// 中兴/诺西/贝尔/烽火的新算法ObjectID
									if ((this.vendorInfoByVendorTagIndex == 2 || this.vendorInfoByVendorTagIndex == 3
											|| this.vendorInfoByVendorTagIndex == 5 || this.vendorInfoByVendorTagIndex == 6)
											&& objIDValues[0].length() > 7) {
										try {
											Long nObjectID = Long.parseLong(objIDValues[0]);
											Long nEnbid = ((nObjectID >> 8) & 0xFFFFF);
											Long nCellid = (nObjectID & 0xFF);

											// if (nEnbid != null && (this.ENB_ID == null || this.ENB_ID.length() < 1)) {
											if (nEnbid > 0) {
												this.ENB_ID = nEnbid.toString();
											}

											this.objLabelAttrValueMap.put("CELL_NO", nCellid.toString());
										} catch (NumberFormatException e) {
										}
									}
								}

								if (objIDValues.length > 1 && objIDValues[1] != null) {
									this.objLabelAttrValueMap.put("EARFCN", objIDValues[1]);
								}

								if (objIDValues.length > 2 && objIDValues[2] != null) {
									this.objLabelAttrValueMap.put("SUBFRAMENBR", objIDValues[2]);
								}
							}
						} else if ("smr".equalsIgnoreCase(elTagName)) {
							// 解析字段名
							String columnsText = reader.getElementText().trim().toUpperCase();
							this.columns = null;
							// TODO: 只保留有RSRP的那一段，其余的不用解
							if (!this.isMREFile) {
								if (columnsText.indexOf("MR.LTESCRSRP") < 0) {
									columnsText = null;
								}
							}

							if (columnsText != null && columnsText.length() > 0) {
								this.columns = columnsText.split(" ");
							}
						} else if ("eNB".equalsIgnoreCase(elTagName)) {
							// 解析eNodeB ID
							this.ENB_ID = reader.getAttributeValue(null, "id");
							if (this.ENB_ID == null || this.ENB_ID.length() < 1) {
								this.ENB_ID = reader.getAttributeValue(null, "MR.eNBId");
							}
						} else if ("fileHeader".equalsIgnoreCase(elTagName)) {
							/**
							 * 从fileHeader中截取version和默认的DataTime; 分解下列标签属性 <FileHeader fileFormatVersion="V1.0.3" reportTime="2014-03-06T14:30:00.000"
							 * startTime="2014-03-06T14:15:00.000" endTime="2014-03-06T14:30:00.000" period="15" jobid="0"></FileHeader>
							 */
							this.version = reader.getAttributeValue(null, "fileFormatVersion");
							String timeStamp = reader.getAttributeValue(null, "startTime");
							this.setCurrDataTimeBytimeStamp(timeStamp);
						}
						break;
					}
					case XMLStreamConstants.END_ELEMENT : {
						if ("eNB".equalsIgnoreCase(elTagName)) {
							this.columns = null;
							this.ENB_ID = null;
						} else if ("object".equalsIgnoreCase(elTagName)) {
							if (this.columns == null || this.columns.length < 2)
								continue;

							// 对mr记录进行加工(网元关联,邻区合并，定位)
							mrRecordProcess();
							// 每个object合并后处输出一条记录
							return (this.lstSubNeMrRecord.size() > 0);
						}

						break;
					}
					default :
						break;
				}
			}
		} catch (XMLStreamException e) {
			this.cause = "【" + myName + "】IO读文件发生异常：" + e.getMessage();
			throw e;
		}

		return false;
	}

	/*
	 * mr记录加工
	 */
	private void mrRecordProcess() {
		if (vLableMrDataRecordMap.size() < 1 || lstSubNeMrRecord.size() < 1)
			return;

		// 网元关联
		String vendor = vendorTag[this.vendorInfoByVendorTagIndex][2];
		String cellId = objLabelAttrValueMap.get("CELL_NO");
		// 查找主小区网元
		LteCellCfgInfo neInfo = LteCellCfgCache.findNeCellByVendorEnbCell(vendor, this.ENB_ID, cellId);
		Long postionParam = null;
		if (neInfo != null) {
			++matchNeInfoSucessRecordNum;
			vLableMrDataRecordMap.put("NE_ENB_ID", String.valueOf(neInfo.neEnbId));
			vLableMrDataRecordMap.put("NE_CELL_ID", String.valueOf(neInfo.neCellId));
			vLableMrDataRecordMap.put("CITY_ID", String.valueOf(neInfo.cityId));

			if (neInfo.location_type != null) {
				if (neInfo.location_type.equals("室内"))
					vLableMrDataRecordMap.put("LOCATION_TYPE", "1");
				else if (neInfo.location_type.equals("室外"))
					vLableMrDataRecordMap.put("LOCATION_TYPE", "2");
			}

			// 查找邻区网元
			postionParam = LteNeiCellCfgDynamicCache.findNeiCellsByVendorEnbCell(vendor, this.ENB_ID, cellId);

			// 定位处理
			LTEOrientUtil p = new LTEOrientUtil(task);
			List<ONEWAYDELAY_CELL> accessCelll = new ArrayList<ONEWAYDELAY_CELL>();
			// 添加主小区的定位参数信息(从第一条记录获取)
			{
				Double fRsrp = null;
				Double fTA = null;
				// 这里原来为"MR.LTESCRSRQ"，后确认需要改为"MR.LTESCRSRP".
				// String value = ensureNotNullValue(this.vLableMrDataRecordMap.get("MR.LTESCRSRQ"));
				String value = ensureNotNullValue(this.vLableMrDataRecordMap.get("MR.LTESCRSRP"));
				if (value.length() > 0)
					fRsrp = Double.parseDouble(value);

				value = ensureNotNullValue(this.vLableMrDataRecordMap.get("MR.LTESCTADV"));
				if (value.length() > 0)
					fTA = Double.parseDouble(value);

				ONEWAYDELAY_CELL mainOnwayDelayCell = this.createOnwayDelayCell(p, neInfo, fRsrp, fTA);
				if (mainOnwayDelayCell != null) {
					accessCelll.add(mainOnwayDelayCell);
				} else {
					++invalidNeInfoRecordNum;
				}
			}

			// 添加邻区的定位参数信息
			if (postionParam != null) {
				int nNECellStartPos = LteNeiCellCfgDynamicCache.NeiCellCache.parsePosParamStart(postionParam);
				int nNeCellCount = LteNeiCellCfgDynamicCache.NeiCellCache.parsePosParamLength(postionParam);
				for (Map<String, String> neRecordMap : lstSubNeMrRecord) {
					String value = ensureNotNullValue(neRecordMap.get("MR.LTENCRSRP"));
					if (value.length() < 1)
						continue;
					Double fNcRsrp = Double.parseDouble(value);

					value = ensureNotNullValue(neRecordMap.get("MR.LTENCPCI"));
					if (value.length() < 1)
						continue;
					Integer ncPCI = Integer.parseInt(value);

					if (fNcRsrp == null || ncPCI == null)
						continue;

					// 在邻区中找出pci相等且距离最近的那个邻区
					LteNeiCellCfgInfo minDistanceNeiInfo = null;
					// for (LteNeiCellCfgInfo neiInfo: neiInfoList) {
					for (int offset = 0; offset < nNeCellCount; ++offset) {
						LteNeiCellCfgInfo neiInfo = LteNeiCellCfgDynamicCache.neiCellCache.neiCellCfgInfos[nNECellStartPos + offset];
						if (neiInfo.nei_pci == ncPCI && neiInfo.distance > 0) {
							if (minDistanceNeiInfo == null)
								minDistanceNeiInfo = neiInfo;

							if (minDistanceNeiInfo.distance > neiInfo.distance)
								minDistanceNeiInfo = neiInfo;
						}
					}

					if (minDistanceNeiInfo != null) {
						ONEWAYDELAY_CELL onwayDelayCell = this.createOnwayDelayCell(p, minDistanceNeiInfo.getCellInfo(), fNcRsrp, null);
						if (onwayDelayCell != null) {
							accessCelll.add(onwayDelayCell);
						}
					}
				}
			}

			// 定位
			if (accessCelll.size() > 0 && (nMinOrientNeiCellsNumber < 1 || accessCelll.size() >= (nMinOrientNeiCellsNumber + 1))) {
				orientation(p, neInfo, accessCelll, this.vLableMrDataRecordMap);

				// accessCelll有一个是主小区，所以需要用>号比较
				if (accessCelll.size() > 2)
					++moreThan2NeiCellsRecordNum;
			}
		}

		// 进行邻区MR记录合并 (如果只有一条不用合并)
		if (lstSubNeMrRecord.size() > 1) {
			StringBuilder sb = new StringBuilder();
			// "MR.LTESCRSRQ", 主小区rsrq不进行合并
			final String[] mergeFields = new String[]{"MR.LTENCRSRP", "MR.LTENCRSRQ", "MR.LTENCPCI", "MR.LTENCEARFCN",};

			for (String mergeField : mergeFields) {
				sb.setLength(0);
				for (int i = 0; i < lstSubNeMrRecord.size(); i++) {
					Map<String, String> neRecordMap = lstSubNeMrRecord.get(i);
					/*
					 * } for (Map<String, String> neRecordMap : lstSubNeMrRecord) {
					 */
					String value = ensureNotNullValue(neRecordMap.get(mergeField));
					if (sb.length() > 0) {
						sb.append(neFieldSplitChar);
					}
					sb.append(value);
					// 目前邻区最多只取8组数据
					if (i <= 7 && !"".equals(value)) {
						vLableMrDataRecordMap.put(mergeField + (i + 1), value);
					}
				}
				this.vLableMrDataRecordMap.put(mergeField, sb.toString());
			}

			// 确保每行有值
			for (int i = 0; i < columns.length; ++i) {
				boolean bMergeField = false;
				for (String mergeField : mergeFields) {
					if (columns[i].equals(mergeField)) {
						bMergeField = true;
						break;
					}
				}

				if (bMergeField)
					continue;

				String value = vLableMrDataRecordMap.get(columns[i]);
				if (value != null && value.length() > 0)
					continue;

				// lstSubNeMrRecord的第一行就是vLableMrDataRecordMap
				for (int row = 1; row < lstSubNeMrRecord.size(); ++row) {
					Map<String, String> vNextRecord = lstSubNeMrRecord.get(row);
					value = vNextRecord.get(columns[i]);
					if (value == null || value.length() < 1)
						continue;

					vLableMrDataRecordMap.put(columns[i], value);
				}
			}
		}

		// IMSI MSISDN关联
		if (imsiQueryHelper != null && isMREFile != null && !isMREFile) {
			String mmeueS1apid = objLabelAttrValueMap.get("MMEUES1APID");
			String mtmsi = null;	// objLabelAttrValueMap.get("TMSI");
			String mmegi = objLabelAttrValueMap.get("MMEGROUPID");
			String mmec = objLabelAttrValueMap.get("MMECODE");

			// 20150330004754
			Date dateTimeOfCallConn = null;
			if (this.currRecordTimeStamp != null) {
				dateTimeOfCallConn = new Date(this.currRecordTimeStamp.getTime());
			}

			try {
				if (imsiQueryHelper != null && dateTimeOfCallConn != null && (mtmsi != null || mmeueS1apid != null) && mmegi != null && mmec != null) {
					ImsiRequestResult result = imsiQueryHelper.matchIMSIInfo(dateTimeOfCallConn.getTime()/* + zoneTimeOffset */,
							LteCoreCommonDataManager.converToLong(mmeueS1apid), LteCoreCommonDataManager.converToLong(mtmsi),
							LteCoreCommonDataManager.converToInteger(mmegi), LteCoreCommonDataManager.converToInteger(mmec));

					if (result != null && result.value == ImsiRequestResult.RESPONSE_IMSI_QUERY_SUCCESS) {
						String imsi = String.valueOf(result.imsi);
						vLableMrDataRecordMap.put("IMSI", imsi);
						vLableMrDataRecordMap.put("MSISDN", result.msisdn);
					}
				}
			} catch (Exception e) {
			}
		}
	}

	private void setCurrDataTimeBytimeStamp(String timeParteen) {
		if (timeParteen != null && timeParteen.length() >= 19) {
			// 2014-07-30T18:15:00.000
			try {
				timeParteen = timeParteen.replace("T", " ");

				// TimeStamp="2016-03-04T13:24:11.543+08:00" 贝尔的时间需要特殊处理
				if (this.vendorInfoByVendorTagIndex == 5 && timeParteen.indexOf("+") > -1) {
					timeParteen = timeParteen.substring(0, timeParteen.indexOf("+"));
				}

				this.currRecordTimeStamp = TimeUtil.getDate(timeParteen);
				if (timeParteen.length() > 20) {
					long nMillSeconds = Long.parseLong(timeParteen.substring(20));
					this.currRecordTimeStamp = new Date(this.currRecordTimeStamp.getTime() + nMillSeconds);
				}
			} catch (Exception e) {

			}
		}
	}

	public Date parseLteMrFileDateTime(String fileName) {
		String patternTime = StringUtil.getPattern(fileName, "20\\d{12}");
		Date fileTime = null;
		if (patternTime != null) {
			try {
				fileTime = TimeUtil.getyyyyMMddHHmmssDate(patternTime);
			} catch (ParseException e) {
				LOGGER.error("从lte mr文件名:{}中获取时间失败.", fileName);
			}
		} else {
			LOGGER.error("从lte mr文件名:{}中获取时间失败.", fileName);
		}

		return fileTime;
	}

	public String getDateString(Date date) {
		if (date == null)
			return null;

		return TimeUtil.getDateString(date);
	}

	public String ensureNotNullValue(String value) {
		if (value == null)
			return "";

		value = value.trim();
		if (value.length() == 3 && value.equalsIgnoreCase("NIL"))
			return "";

		return value;
	}
}
