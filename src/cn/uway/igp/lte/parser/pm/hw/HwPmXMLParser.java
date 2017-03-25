package cn.uway.igp.lte.parser.pm.hw;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.igp.lte.templet.xml.HwPmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * @author yuy 2014.3.5 lte 华为性能解码器(xml格式)
 */
public class HwPmXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(HwPmXMLParser.class);

	/**
	 * 网元级别
	 */
	public String neType;

	/**
	 * 网元名称
	 */
	public String neName;

	/**
	 * 性能指标类别
	 */
	public String measInfoId;

	/**
	 * 性能指标
	 */
	public String measTypes;

	/**
	 * 名称
	 */
	public String measObjLdn;

	/**
	 * 性能指标结果集
	 */
	public String measResults;

	/**
	 * 存放一条数据
	 */
	public Map<String, String> resultMap;

	/**
	 * 开始时间
	 */
	private Date beginTime;

	/**
	 * 输入zip流
	 */
	public GZIPInputStream gzipstream;

	/**
	 * xml流
	 */
	public XMLStreamReader reader;

	/**
	 * 模板传入接口
	 * 
	 * @param tmpfilename
	 */
	public HwPmXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void before() {
		super.before();
		try {
			// GZ解压
			gzipstream = new GZIPInputStream(inputStream);
		} catch (IOException e) {
			throw new IllegalArgumentException("处理中兴性能文件失败", e);
		}
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();

		reader = XMLInputFactory.newInstance().createXMLStreamReader(gzipstream);
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		int type = -1;
		resultMap = new HashMap<String, String>();
		try {
			while (reader.hasNext()) {
				type = reader.next();
				String tagName = null;

				// 只取开始和结束标签
				if (type == XMLStreamConstants.START_ELEMENT || type == XMLStreamConstants.END_ELEMENT)
					tagName = reader.getLocalName();

				if (tagName == null) {
					continue;
				}
				switch (type) {
					case XMLStreamConstants.START_ELEMENT :
						if (tagName.equalsIgnoreCase("measTypes")) {
							// 获取性能各指标名称
							measTypes = StringUtil.nvl(reader.getElementText(), "");
							break;
						}
						if (tagName.equalsIgnoreCase("measValue")) {
							measObjLdn = StringUtil.nvl(reader.getAttributeValue(null, "measObjLdn"), "");
							break;
						}
						if (tagName.equalsIgnoreCase("measResults")) {
							// 获取性能各指标结果集
							measResults = StringUtil.nvl(reader.getElementText(), "");
							break;
						}
						if (tagName.equalsIgnoreCase("measInfo")) {
							// 获取性能指标类别
							measInfoId = StringUtil.nvl(reader.getAttributeValue(null, "measInfoId"), "");
							if (!findMyTemplet(measInfoId)) {
								// 找不到对应的模板，丢弃
								measInfoId = null;
								continue;
							}
							break;
						}
						if (tagName.equalsIgnoreCase("fileSender")) {
							// 获取网元级别（cell/eNodeB）
							neType = StringUtil.nvl(reader.getAttributeValue(null, "elementType"), "");
							break;
						}
						if (tagName.equalsIgnoreCase("managedElement")) {
							// 获取网元名称
							neName = StringUtil.nvl(reader.getAttributeValue(null, "userLabel"), "");
							break;
						}
						if (tagName.equals("measCollec") && this.beginTime == null) {
							beginTime = strToDate(reader.getAttributeValue(null, "beginTime"));
							// currentDataTime = beginTime;
							break;
						}
						break;

					case XMLStreamConstants.END_ELEMENT :
						// 发现结束标签为measValue，此时提交一条数据
						if (tagName.equalsIgnoreCase("measValue")) {
							// 指标类别为空，丢弃该条数据
							if (measInfoId == null)
								continue;
							resultMap.put("measObjLdn".toUpperCase(), measObjLdn);
							resultMap.put("beginTime".toUpperCase(), TimeUtil.getDateString(beginTime));
							resultMap.put("OBJECTNO", "0");
							if (measTypes != null && measResults != null) {
								String types[] = StringUtil.split(measTypes.toUpperCase().trim(), " ");
								String results[] = StringUtil.split(measResults.toUpperCase().trim(), " ");
								for (int n = 0; n < types.length && n < results.length; n++) {
									resultMap.put(types[n], results[n]);
								}
							}
							measObjLdn = null;
							measResults = null;
							return true;
						}
						// 发现结束标签为measInfo
						if (tagName.equalsIgnoreCase("measInfo")) {
							measTypes = null;
							break;
						}
						break;

					default :
						break;
				}
			}
		} catch (XMLStreamException e) {
			this.cause = "【华为性能XML解码】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		readLineNum++;
		ParseOutRecord record = new ParseOutRecord();
		List<Field> fieldList = templet.getFieldList();
		// Map<String, String> map = new HashMap<String, String>();
		Map<String, String> map = this.createExportPropertyMap(templet.getDataType());
		for (Field field : fieldList) {
			if (field == null) {
				continue;
			}
			String value = resultMap.get(field.getName().toUpperCase());
			// 找不到，设置为空
			if (value == null) {
				map.put(field.getIndex(), "");
				continue;
			}
			if ("NIL".equalsIgnoreCase(value))
				value = "";

			// 字段值处理
			if (!fieldValHandle(field, value, map)) {
				invalideNum++;
				return null;
			}

			map.put(field.getIndex(), value);
		}
		
		// 公共回填字段
		map.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		String time = TimeUtil.getDateString(this.currentDataTime);
		map.put("BEGINTIME", time);
		map.put("STARTTIME", time);

		handleTime(map);
		record.setType(templet.getDataType());
		record.setRecord(map);
		return record;
	}

	/**
	 * 找到当前对应的Templet
	 */
	public boolean findMyTemplet(String measInfoId) {
		templet = templetMap.get(measInfoId);// 这里的key全部转为大写字母
		if (templet == null) {
			// throw new NullPointerException("没有找到对应的模板，解码退出");
			LOGGER.debug("没有找到对应的模板，跳过，measInfoId:{}", measInfoId);
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
			String fileName = FileUtil.getFileName(this.rawName);// A20140504.0000+0800-0100+0800_L昌北区财大第五教学楼-XY.xml
			String patternTime = StringUtil.getPattern(fileName, "\\d{8}[.]\\d{4}");
			patternTime = patternTime.replace(".", "_");
			this.currentDataTime = TimeUtil.getyyyyMMdd_HHmmDate(patternTime);
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();

		LOGGER.debug("[{}]-华为性能XML解析，处理{}条记录", new Object[]{task.getId(), readLineNum});
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		// 解析模板
		HwPmXmlTempletParser hwPmXmlTempletParser = new HwPmXmlTempletParser();
		hwPmXmlTempletParser.tempfilepath = templates;
		hwPmXmlTempletParser.parseTemp();

		templetMap = hwPmXmlTempletParser.getTemplets();
	}

	/**
	 * 把"2010-02-03T10:30:00+08:00"这类格式的字符串转为日期
	 * 
	 * @param time
	 * @return
	 * @throws Exception
	 */
	private static Date strToDate(String time) throws Exception {
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.YEAR, Integer.parseInt(time.substring(0, 4)));
		calendar.set(Calendar.MONTH, Integer.parseInt(time.substring(5, 7)) - 1);
		calendar.set(Calendar.DATE, Integer.parseInt(time.substring(8, 10)));
		calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(time.substring(11, 13)));
		calendar.set(Calendar.MINUTE, Integer.parseInt(time.substring(14, 16)));
		calendar.set(Calendar.SECOND, 0);
		return calendar.getTime();
	}
}
