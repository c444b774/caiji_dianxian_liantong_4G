package cn.uway.igp.lte.parser.mr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.accessor.StreamAccessOutObject;
import cn.uway.framework.parser.AbstractParser;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.templet.CSVCfcTempletParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgCache;
import cn.uway.igp.lte.extraDataCache.cache.LteCellCfgInfo;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class MRSXmlParser extends AbstractParser {

	private static Logger LOGGER = LoggerFactory.getLogger(MRSXmlParser.class);

	public StreamAccessOutObject streamOut;

	// 解析的文件
	public String rawFilePath = null;

	// 文件名
	public String rawFileName = null;

	// 网络制式
	public Short lteFddTdd;

	public Map<String, Templet> templetMap = null;

	/**
	 * <数据类型，模板配置解析字段>
	 */
	public HashMap<String, ParseTemplet> tempFieldMap = new HashMap<String, ParseTemplet>();

	/**
	 * <解析字段名，模板解析字段信息>
	 */
	public ParseTemplet parseTemplet = null;

	public BufferedReader reader = null;

	/**
	 * 开始时间
	 */
	public String mrsStartTime = null;

	public static final String START_TIME_TAG = "startTime=\"";

	public static final int START_TIME_TAG_LEN = START_TIME_TAG.length();

	/**
	 * enb_id
	 */
	public String enbId = null;

	public static final String ENB_TAG = "<ENB ";

	public static final int ENB_TAG_LEN = ENB_TAG.length();
	
	public static final String ENB_TAG_ID="ID=\"";
	public static final String ENB_TAG_ID2="MR.ENBId=\"";
	
	public static final int ENB_TAG_ID_LEN = ENB_TAG_ID.length();
	public static final int ENB_TAG_ID_LEN2 = ENB_TAG_ID2.length();

	/**
	 * 数据类型标识
	 */
	public String className = null;

	public static final String CLASS_NAME_TAG = "mrName=\"";

	public static final int CLASS_NAME_TAG_LEN = CLASS_NAME_TAG.length();

	/**
	 * 数据类型结束标识
	 */
	public static final String CLASS_NAME_END_TAG = "</measurement>";

	/**
	 * 列头字段名称信息
	 */
	public String[] fields = null;

	public static final String FIELD_NAME_TAG = "<smr>";

	public static final int FIELD_NAME_TAG_LEN = FIELD_NAME_TAG.length();

	/**
	 * measObjLdn字段信息
	 */
	public String objLdn = null;

	public static final String OBJ_LDN_TAG = "<object id=\"";
	public static final String OBJ_LDN_TAG2 = "<object MR.objectId=\"";

	public static final int OBJ_LDN_TAG_LEN = OBJ_LDN_TAG.length();
	public static final int OBJ_LDN_TAG_LEN2 = OBJ_LDN_TAG2.length();

	/**
	 * 解析字段内容值
	 */
	public String[] fieldValues = null;

	public static final String FIELD_VALUE_TAG = "<v>";

	public static final int FIELD_VALUE_TAG_LEN = FIELD_VALUE_TAG.length();

	/**
	 * 一条数据的结束标记
	 */
	public static final String LINE_RECORD_END_TAG = "</object>";

	public long readLineNum = 0; // 记录总行数

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
				this.reader = new BufferedReader(new InputStreamReader(zipInput, "GBK"), 16 * 1024);
			else {
				LOGGER.warn("压缩包中无文件，文件解析异常。文件：{}", this.rawFilePath);
				return;
			}
		} else if (accessOutObject.getRawAccessName().endsWith(".gz")) {
			this.reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(this.streamOut.getOutObject()), "UTF-8"), 16 * 1024);
		} else {
			this.reader = new BufferedReader(new InputStreamReader(this.streamOut.getOutObject(), "UTF-8"), 16 * 1024);
		}
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		int startIndex = -1;
		String lineRecord = null;
		try {
			while ((lineRecord = this.reader.readLine()) != null) {
				// object id字段信息
				startIndex = lineRecord.indexOf(OBJ_LDN_TAG);
				if (startIndex != -1) {
					this.objLdn = lineRecord.substring(startIndex + OBJ_LDN_TAG_LEN, lineRecord.indexOf("\">")).trim();
					continue;
				} else {
					startIndex = lineRecord.indexOf(OBJ_LDN_TAG2);
					if (startIndex != -1) {
						this.objLdn = lineRecord.substring(startIndex + OBJ_LDN_TAG_LEN2, lineRecord.indexOf("\">")).trim();
						continue;
					}					
				}
				
				// 解析字段内容值
				startIndex = lineRecord.indexOf(FIELD_VALUE_TAG);
				if (startIndex != -1) {
					String fieldValueStr = lineRecord.substring(startIndex + FIELD_VALUE_TAG_LEN, lineRecord.indexOf("</v>")).trim();
					this.fieldValues = StringUtil.split(fieldValueStr, " ");
					continue;
				}
				// 读取一条数据的结束标记
				if (lineRecord.indexOf(LINE_RECORD_END_TAG) != -1) {
					return true;
				}
				// 解析列头字段名称
				startIndex = lineRecord.indexOf(FIELD_NAME_TAG);
				if (startIndex != -1) {
					String fieldHeadStr = lineRecord.substring(startIndex + FIELD_NAME_TAG_LEN, lineRecord.indexOf("</smr>")).trim()
							.toUpperCase();
					this.fields = StringUtil.split(fieldHeadStr, " ");
					continue;
				}
				// 测量数据类型
				startIndex = lineRecord.indexOf(CLASS_NAME_TAG);
				if (startIndex != -1) {
					this.className = lineRecord.substring(startIndex + CLASS_NAME_TAG_LEN, lineRecord.indexOf("\">"));
					continue;
				}
				// 读取类型数据结束标记，清空读取信息
				if (lineRecord.indexOf(CLASS_NAME_END_TAG) != -1) {
					this.className = null;
					this.fields = null;
					this.objLdn = null;
					this.fieldValues = null;
				}
				// 解析MRS开始时间
				startIndex = lineRecord.indexOf(START_TIME_TAG);
				if (startIndex != -1) {
					this.mrsStartTime = lineRecord.substring((startIndex + START_TIME_TAG_LEN), (startIndex + START_TIME_TAG_LEN + 19))
							.replace('T', ' ');
					continue;
				}
				// 解析MRS ENB_ID
				String upperCaseLine = lineRecord.toUpperCase();
				startIndex = upperCaseLine.indexOf(ENB_TAG);
				if (startIndex != -1) {
					int idIndex = upperCaseLine.indexOf(ENB_TAG_ID, startIndex+ENB_TAG_LEN);
					if (idIndex > 0) {
						int cutPos = idIndex + ENB_TAG_ID_LEN;
						this.enbId = upperCaseLine.substring(cutPos, upperCaseLine.indexOf("\"", cutPos));
						continue;
					} else {
						idIndex = upperCaseLine.indexOf(ENB_TAG_ID2, startIndex+ENB_TAG_LEN);
						if (idIndex > 0) {
							int cutPos = idIndex + ENB_TAG_ID_LEN2;
							this.enbId = upperCaseLine.substring(cutPos, upperCaseLine.indexOf("\"", cutPos));
							continue;
						}
					}
				}
			}
		} catch (Exception e) {
			LOGGER.error("文件解析异常。文件：{}，异常信息：{}", this.rawFilePath, e.getMessage());
			return false;
		}
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		// 查找解析模板
		if (!this.findMyTemplet()) {
			this.invalideNum++;
			return null;
		}
		Map<String, String> recordData = this.createExportPropertyMap(this.parseTemplet.dataType);
		HashMap<String, Field> fieldsInfo = this.parseTemplet.fieldsInfo;
		String cellNo = null;
		try {
			for (int i = 0; i < this.fields.length; i++) {
				Field field = fieldsInfo.get(this.fields[i]);
				if (field == null) {
					continue;
				}
				String value = this.fieldValues[i].trim();
				if ("".equals(value) || "NIL".equalsIgnoreCase(value)) {
					continue;
				}
				recordData.put(field.getIndex(), value);
			}
			String[] strs = null;
			if (this.objLdn.indexOf(":") != -1) {
				strs = StringUtil.split(this.objLdn, ":");
				cellNo = strs[0];
				if (cellNo != null)
				{
					// "258224-3:75:2"
					String[] txtCellNO = StringUtil.split(cellNo, "-");
					if (txtCellNO != null && txtCellNO.length > 1) {
						cellNo = txtCellNO[1];
						recordData.put("CELL_NO", cellNo);
					} else {
						recordData.put("CELL_NO", cellNo);
					}
				}
				
				if (strs.length > 1) {
					recordData.put("EARFCN", strs[1]);
				}
				if (strs.length > 2) {
					recordData.put("SUBFRAMENBR", strs[2]);
				}
				if (strs.length > 3) {
					recordData.put("PRBNBR", strs[3]);
				}
			} else if (this.objLdn.indexOf("-") != -1) {
				strs = StringUtil.split(this.objLdn, "-");
				if (strs.length > 1) {
					cellNo = strs[1];
					recordData.put("CELL_NO", cellNo);
				}
			} else {
				//Long enbid = Long.parseLong(this.enbId);
				Long objid = Long.parseLong(this.objLdn);
				//cellNo =  String.valueOf(objid - (enbid << 8));
				cellNo =  String.valueOf(objid & 0xFF);
				recordData.put("CELL_NO", cellNo);
			}
		} catch (Exception e) {
			LOGGER.warn("测量xml文件解析字段拆封失败。objLdn：{}、文件名：{}、异常信息：{}", new Object[]{this.objLdn, this.rawFileName, e.getMessage()});
			this.invalideNum++;
			return null;
		}
		
		recordData.put("CITY_ID", String.valueOf(task.getExtraInfo().getCityId()));
		
		// 网元关联NE_ENB_ID、NE_CELL_ID
		LteCellCfgInfo lteCellCfgInfo = LteCellCfgCache.findNeCellByVendorEnbCell(task.getExtraInfo().getVendor(), this.enbId, cellNo);
		if (lteCellCfgInfo != null) {
			recordData.put("NE_ENB_ID", String.valueOf(lteCellCfgInfo.neEnbId));
			recordData.put("NE_CELL_ID", String.valueOf(lteCellCfgInfo.neCellId));
			recordData.put("CITY_ID", String.valueOf(lteCellCfgInfo.cityId));
		}
		// 公共回填字段
		recordData.put("ENB_ID", this.enbId);
		recordData.put("OBJECT_ID", this.objLdn);
		recordData.put("LTE_FDD_TDD", String.valueOf(this.lteFddTdd));
		recordData.put("VENDOR", task.getExtraInfo().getVendor());
		recordData.put("START_TIME", this.mrsStartTime);
		ParseOutRecord outRecord = new ParseOutRecord();
		outRecord.setType(this.parseTemplet.dataType);
		outRecord.setRecord(recordData);
		readLineNum++;
		return outRecord;
	}

	@Override
	public List<ParseOutRecord> getAllRecords() {
		// TODO 未实现
		return null;
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("[{}]-MRS数据XML解析结束，处理{}条记录", new Object[]{task.getId(), readLineNum});
	}

	@Override
	public Date getDataTime(ParseOutRecord outRecord) {
		return this.currentDataTime;
	}

	/**
	 * 解析文件名
	 * 
	 * @throws Exception
	 */
	public void parseFileName() {
		// 文件名：FDD-LTE_MRS_ZTE_OMCR1_426605_20140215074500.xml || TD-LTE_MRS_DATANG_OMC_589051_20140813184500.zip
		try {
			String patternTime = StringUtil.getPattern(this.rawFileName, "\\d{14}");
			this.currentDataTime = TimeUtil.getDate(patternTime, "yyyyMMddHHmmss");
			int startIndex = this.rawFileName.indexOf("-LTE");
			if (startIndex == -1 || 0 == startIndex) {
				startIndex = this.rawFileName.indexOf("_LTE");
			}
			if (startIndex == -1 || 0 == startIndex) {
				return;
			}
			String str = this.rawFileName.substring(0, startIndex);
			if ("FDD".equals(str) || "FD".equals(str)) {
				this.lteFddTdd = 0;
			} else if ("TDD".equals(str) || "TD".equals(str)) {
				this.lteFddTdd = 1;
			}
		} catch (Exception e) {
			LOGGER.error("文件名{}，解析文件名异常。{}", this.rawFileName, e.getMessage());
		}
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public final void parseTemplet() throws Exception {
		// 解析模板
		TempletParser csvTempletParser = new CSVCfcTempletParser();
		csvTempletParser.tempfilepath = templates;
		csvTempletParser.parseTemp();
		templetMap = csvTempletParser.getTemplets();
	}

	/**
	 * 找到当前对应的Templet
	 */
	public final boolean findMyTemplet() {
		this.parseTemplet = this.tempFieldMap.get(this.className);
		if (this.parseTemplet != null) {
			return true;
		}
		Templet templet = this.templetMap.get(this.className);
		if (templet == null) {
			//LOGGER.error("xml文件读取MRS类型数据未找到模板信息，跳过数据类型。className：{}、文件名：{}", new Object[]{this.className, this.rawFileName});
			return false;
		}
		this.parseTemplet = new ParseTemplet();
		this.parseTemplet.dataType = templet.dataType;
		for (Field field : templet.fieldList) {
			if (field == null) {
				continue;
			}
			this.parseTemplet.fieldsInfo.put(field.getName(), field);
		}
		this.tempFieldMap.put(this.className, this.parseTemplet);
		return true;
	}

	class ParseTemplet {

		public int dataType = -100;

		/**
		 * <解析字段名，模板解析字段信息>
		 */
		public HashMap<String, Field> fieldsInfo = new HashMap<String, Field>();;
	}
}
