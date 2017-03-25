package cn.uway.igp.lte.parser.pm.hw;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.CSVCfcTempletParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class HwPmCSVGdParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(HwPmCSVGdParser.class);

	/**
	 * className
	 */
	public String className = null;

	/** 输入zip流 */
	public ZipInputStream zipstream;

	/**
	 * 解压缩文件对象
	 */
	public ZipEntry entry = null;

	public String[] fields = null;

	public Map<String, String> recordResult = new HashMap<String, String>();

	/**
	 * 一条数据
	 */
	public String lineRecord = null;

	public HwPmCSVGdParser(String tmpfilename) {
		super(tmpfilename);
	}

	public HwPmCSVGdParser() {
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.before();
		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());
		// 解析模板 获取当前文件对应的templet
		parseTemplet();
		this.zipstream = new ZipInputStream(this.inputStream);
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		try {
			if (null != this.reader && (this.lineRecord = this.reader.readLine()) != null) {
				return true;
			}
			return parserZipStream();
		} catch (Exception e) {
			this.cause = "广东-电信-华为性能csv解析异常，classname:{" + this.className + "}、fileName:{" + this.rawName + "}。异常：" + e.getMessage();
			LOGGER.error(cause);
			throw e;
		}
	}

	/**
	 * 解析压缩包
	 * 
	 * @return
	 * @throws Exception
	 */
	public final boolean parserZipStream() throws Exception {
		while ((this.entry = zipstream.getNextEntry()) != null) {
			this.rawName = this.entry.getName();
			// 解析文件名
			this.parseFileName();
			// 查找解析模板
			if (!this.findMyTemplet(this.className)) {
				continue;
			}
			// 转换为缓冲流读取
			this.reader = new BufferedReader(new InputStreamReader(this.zipstream, "GBK"), 16 * 1024);
			this.lineRecord = this.reader.readLine();
			if (null == this.lineRecord || "".equals(this.lineRecord.trim())) {
				LOGGER.error("csv文件解析异常。文件名：{}、lineRecord：", new Object[]{this.rawName, this.lineRecord});
				continue;
			}
			this.lineRecord = this.lineRecord.replace("\"", "");
			this.fields = StringUtil.split(this.lineRecord.toUpperCase(), ",");
			// 读取一条无效记录丢弃，内容:",分钟,,,无,无,无,无"。
			this.reader.readLine();
			this.lineRecord = this.reader.readLine();
			// LOGGER.debug("广东-电信-华为性能csv解析，已经解析到fileName:{}。", this.rawName);
			return true;
		}
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		ParseOutRecord record = null;
		Map<String, String> recordData = null;
		try {
			// 解析一行记录
			this.lineRecord = FileParser.switchLine(this.lineRecord, "^");
			String[] values = StringUtil.split(this.lineRecord, "^");
			record = new ParseOutRecord();
			recordData = this.createExportPropertyMap(this.templet.getDataType());
			recordData.put("STAMPTIME", values[0]);
			List<Field> fieldList = this.templet.getFieldList();
			for (Field field : fieldList) {
				if (null == field || null == field.getName() || "".equals(field.getName().trim())) {
					continue;
				}
				int i = 0;
				for (; i < this.fields.length; i++) {
					if (field.getName().equals(this.fields[i])) {
						break;
					}
				}
				if (i == this.fields.length)
					continue;
				String value = values[i];
				recordData.put(field.getIndex(), value);
				// 是否拆封字段
				if ("true".equals(field.getIsSplit())) {
					// "对象名称"拆封
					if ("对象名称".equalsIgnoreCase(field.getName())) {
						if (!this.splitMeasObjLdnStr(value, field, recordData)) {
							LOGGER.error("节点：{}，fdn：{}，拆封失败。文件名：{}", new Object[]{this.className, value, this.rawName});
							this.invalideNum++;
							return null;
						}
					}
				}
			}
		} catch (Exception e) {
			LOGGER.error("广东-电信-华为性能csv解析异常，fileName：{}、lineRecord：{}。", new Object[]{this.rawName, this.lineRecord});
			throw e;
		}
		// 公共回填字段
		recordData.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		recordData.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		recordData.put("STARTTIME", TimeUtil.getDateString(this.currentDataTime));
		recordData.put("OBJECTNO", "0");
		record.setType(templet.getDataType());
		record.setRecord(recordData);
		readLineNum++;
		return record;
	}

	/**
	 * "对象名称"拆封
	 * 
	 * @param value
	 * @param field
	 * @param recordData
	 */
	public final boolean splitMeasObjLdnStr(String value, Field field, Map<String, String> recordData) {
		/**
		 * "大学城广工_广工大学城-D-L/小区:eNodeB名称=大学城广工_广工大学城-D-L, 本地小区标识=0, 小区名称=大学城广工_广工大学城-D-L_0, eNodeB标识=483387, 小区双工模式=CELL_FDD" or
		 * "FM_横岗福坑新村/S1接口:eNodeB名称=FM_横岗福坑新村, S1接口标识=6" or ...
		 */
		try {
			String[] strs = value.split("/");
			if (strs.length < 2) {
				return false;
			}
			// 分拆字段列表
			String[] values = StringUtil.split(strs[1], ", ");
			this.recordResult.clear();
			for (int i = 0; i < values.length; i++) {
				int index = values[i].indexOf("=");
				if (index < 1) {
					continue;
				}
				String name = values[i].substring(0, index);
				// "小区:eNodeB名称"处理，截取"eNodeB名称"
				if (name.indexOf(":") != -1) {
					name = name.substring(name.indexOf(":") + 1, name.length());
				}
				this.recordResult.put(name, values[i].substring(index + 1, values[i].length()));
			}
			List<Field> fieldList = field.getSubFieldList();
			for (Field subField : fieldList) {
				if ("OBJECT_NAME".equals(subField.getName())) {
					recordData.put(subField.getIndex(), strs[0]);
				} else {
					String val = this.recordResult.get(subField.getName());
					if (val != null) {
						recordData.put(subField.getIndex(), val);
					}
				}
			}
		} catch (Exception e) {
			LOGGER.error("", e);
			return false;
		}
		return true;
	}

	/**
	 * 找到当前对应的Templet
	 */
	public final boolean findMyTemplet(String className) {
		this.templet = this.templetMap.get(className);
		if (this.templet == null) {
			LOGGER.debug("没有找到对应的模板，跳过。classname:{}、fileName:{}", new Object[]{className, this.rawName});
			return false;
		}
		return true;
	}

	/**
	 * 解析文件名
	 * 
	 * @throws Exception
	 */
	public void parseFileName() {
		try {
			if (this.rawName == null) {
				return;
			}
			// 解析文件名，获得className
			int index = this.rawName.indexOf("pmresult_");
			if (index != -1) {
				String str = this.rawName.substring(index + "pmresult_".length(), this.rawName.length());
				this.className = str.substring(0, str.indexOf("_"));
			} else {
				return;
			}
			String fileName = FileUtil.getFileName(this.rawName);
			String patternTime = StringUtil.getPattern(fileName, "\\d{12}");
			this.currentDataTime = TimeUtil.getyyyyMMddHHmmDate(patternTime);
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("[{}]-广东-电信-华为性能csv解析，处理{}条记录", new Object[]{task.getId(), readLineNum});
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		// 解析模板
		TempletParser csvTempletParser = new CSVCfcTempletParser();
		csvTempletParser.tempfilepath = templates;
		csvTempletParser.parseTemp();
		templetMap = csvTempletParser.getTemplets();
	}

	public static void main(String[] args) throws ParseException {
		String fileName = FileUtil.getFileName("pmresult_1526726657_60_201503111100_201503111200.xml");
		String patternTime = StringUtil.getPattern(fileName, "\\d{12}");
		System.out.println(patternTime);
	}
}
