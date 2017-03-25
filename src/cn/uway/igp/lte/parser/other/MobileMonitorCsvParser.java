package cn.uway.igp.lte.parser.other;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.CSVParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class MobileMonitorCsvParser extends CSVParser {

	private static Logger LOGGER = LoggerFactory.getLogger(MobileMonitorCsvParser.class);

	static SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");

	// 将时间转换成format格式的Date
	public final Date getDateTime(String date, String format) {
		if (date == null) {
			return null;
		}
		if (format == null) {
			format = "yyyy-MM-dd HH:mm";
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
	public void parseTemplet() throws Exception {
		// 解析模板
		TempletParser csvTempletParser = new TempletParser();
		csvTempletParser.tempfilepath = templates;
		csvTempletParser.parseTemp();
		templetMap = csvTempletParser.getTemplets();
		// 获取当前文件对应的Templet
		getMyTemplet();
		if (templet == null) {
			LOGGER.debug("没有找到对应的模板，跳过：" + rawName);
		} else {
			currSplitSign = templet.getSplitSign();
		}
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的Templet
		parseTemplet();

		if (templet.getEncoding() != null)
			reader = new BufferedReader(new InputStreamReader(inputStream, templet.getEncoding()), 16 * 1024);
		else
			reader = new BufferedReader(new InputStreamReader(inputStream), 16 * 1024);

		// 获取头
		try {
			readHead();
		} catch (Exception e) {
		}
		
		if (head == null || head.trim().length()<1) {
			head = null;
			LOGGER.debug("[{}]-获取头line为空，将跳过该文件的采集.", task.getId());
			return;
		}

		// 字段定位
		setFieldLocalMap(head);

		LOGGER.debug("[{}]-获取头line={}", task.getId(), head);
		readLineNum++;
	}
	
	@Override
	public boolean hasNextRecord() throws Exception {
		if (head == null)
			return false;
		
		return super.hasNextRecord();
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		readLineNum++;
		ParseOutRecord record = new ParseOutRecord();
		String tmpLine = switchLineWithSplitSign(currSplitSign, currentLine, splitSign);
		String[] valList = StringUtil.split(tmpLine, splitSign);
		List<Field> fieldList = templet.getFieldList();
		// Map<String, String> map = new HashMap<String, String>();
		Map<String, String> map = this.createExportPropertyMap(templet.getDataType());
		for (Field field : fieldList) {
			if (field == null) {
				continue;
			}
			// 定位，即找出模板中的字段在原始文件中的位置
			Integer indexInLine = fieldLocalMap.get(field.getName());
			// 找不到，设置为空
			if (indexInLine == null) {
				if (map.get(field.getIndex()) != null)
					continue;
				map.put(field.getIndex(), "");
				continue;
			}
			if (indexInLine >= valList.length)
				break;
			String value = valList[indexInLine];
			value = value.replace("\"", "");
			// 字段值处理
			if (!fieldValHandle(field, value, map)) {
				invalideNum++;
				return null;
			}

			if ("-".equals(value) || "null".equalsIgnoreCase(value)) {
				value = null;
			} else if (value.length() == 18 && ("-".equalsIgnoreCase("" + value.charAt(7)) && ":".equalsIgnoreCase("" + value.charAt(12)))) {
				value = value.substring(0, 9) + " " + value.substring(10);
			}

			if ("true".equals(field.getIsPassMS())) {
				int i = value.indexOf(".");
				value = (i == -1 ? value : value.substring(0, i));
			}

			map.put(field.getIndex(), value);
		}

		// 公共回填字段
		map.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		handleTime(map);
		record.setType(templet.getDataType());
		record.setRecord(map);
		return record;
	}

	/**
	 * 获取当前文件对应的Templet
	 */
	public void getMyTemplet() {
		Iterator<String> it = templetMap.keySet().iterator();
		while (it.hasNext()) {
			String file = it.next();
			// 匹配通配符*
			String wildCard = "*";

			if (wildCardMatch(file, rawName, wildCard)) {
				templet = templetMap.get(file);
				// on 20141022 如果不判断为空，后面的会替换前面的。 照成文件和 文件表达式不匹配
				if (templet != null)
					return;
				// end add
			}
		}
	}

	/**
	 * @param regex
	 *            正则表达式
	 * @param input
	 *            输入字符串
	 * @return
	 */
	public static boolean findValue(String regex, String input) {
		Pattern pattern = Pattern.compile(regex);
		Matcher matcher = pattern.matcher(input);
		if (matcher.find()) {
			String result = matcher.group();
			if (!(null == result || "".equals(result.trim()))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 通配符匹配
	 * 
	 * @param src
	 *            解析模板中的文件名或classname
	 * @param dest
	 *            厂家原始文件，如果是压缩包，则会带路径
	 * @param wildCard
	 *            通配符
	 * @return
	 */
	public static boolean wildCardMatch(String src, String dest, String wildCard) {
		boolean flag = false;
		String tmp = src.replace("*", "(.)*");
		if (dest.contains("/")) {
			int lastIndex = dest.lastIndexOf("/");
			String tmpFileNameOrClassName = dest.substring(lastIndex + 1);

			boolean result = findValue(tmp, tmpFileNameOrClassName);
			if (result)
				return true;

		} else {
			boolean result = findValue(tmp, dest);
			if (result)
				return true;
		}
		return flag;
	}

	/**
	 * 解析文件名
	 * 
	 * @throws Exception
	 */
	public void parseFileName() {
		try {
			String fileName = FileUtil.getFileName(this.rawName);
			String patternTime = StringUtil.getPattern(fileName, "\\d{12}");
			if (StringUtil.isNotEmpty(patternTime))
				this.currentDataTime = getDateTime(patternTime, "yyyyMMddHHmm");
			else {
				if (this.rawName.contains("-WEEK")) {
					this.currentDataTime = getDateTime(getNowWeekBegin(), "yyyy-MM-dd HH:mm");
				} else if (this.rawName.contains("-MONTH")) {
					this.currentDataTime = getDateTime(getMonthFirstDay(), "yyyy-MM-dd HH:mm");
				}
			}
		} catch (Exception e) {
			LOGGER.error("解析文件名异常", e);
		}
	}

	/*
	 * 取本周7天的第一天（周一的日期）
	 */
	public static String getNowWeekBegin() {
		int mondayPlus;
		Calendar cd = Calendar.getInstance();
		// 获得今天是一周的第几天，星期日是第一天，星期二是第二天......
		int dayOfWeek = cd.get(Calendar.DAY_OF_WEEK) - 1; // 因为按中国礼拜一作为第一天所以这里减1
		if (dayOfWeek == 1) {
			mondayPlus = 0;
		} else {
			mondayPlus = 1 - dayOfWeek;
		}
		GregorianCalendar currentDate = new GregorianCalendar();
		currentDate.add(GregorianCalendar.DATE, mondayPlus);
		Date monday = currentDate.getTime();

		DateFormat df = DateFormat.getDateInstance();
		String preMonday = df.format(monday);

		return preMonday + " 00:00";

	}

	/**
	 * @return
	 */
	public static String getMonthFirstDay() {

		// 获取前月的第一天
		Calendar cal_1 = Calendar.getInstance();// 获取当前日期
		// cal_1.add(Calendar.MONTH, 1);
		cal_1.set(Calendar.DAY_OF_MONTH, 1);// 设置为1号,当前日期既为本月第一天
		String firstDay = format.format(cal_1.getTime());
		return firstDay;
	}

	public static void main(String[] args) {
		long begin = System.currentTimeMillis();
		String value = "2014-10-1523:59:59";
		// for (int i = 0; i < 10000000; i++) {
		// if (value.length() == 18 && findValue("\\d{4}-\\d{2}-\\d{4}:\\d{2}:\\d{2}", value)) {
		// value = value.substring(0, 9) + " " + value.substring(10);
		// }
		// }
		for (int i = 0; i < 10000000; i++) {
			if (value.length() == 18 && ("-".equalsIgnoreCase("" + value.charAt(7)) && ":".equalsIgnoreCase("" + value.charAt(12)))) {

				value = value.substring(0, 9) + " " + value.substring(10);
			}
		}
		long end = System.currentTimeMillis();
		System.out.println((end - begin) / 1000);
		System.out.println(value);

	}
}
