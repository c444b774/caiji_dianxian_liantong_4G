package cn.uway.igp.lte.parser.pm.hw;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.file.CSVParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * @author yuy 2013.12.27 lte 华为性能解码器
 */
public class HwCSVParser extends CSVParser {

	private static Logger LOGGER = LoggerFactory.getLogger(HwCSVParser.class);

	public HwCSVParser(String tmpfilename) {
		super(tmpfilename);
	}

	/**
	 * 数据业务时间间隔，默认60分钟
	 */
	public int timeSplit = 60;

	/**
	 * 表名ID
	 */
	public String tableId;

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		super.parse(accessOutObject);
		String unit = reader.readLine();
		LOGGER.debug("[{}]-获取单位line={}", task.getId(), unit);
		readLineNum++;
	}

	/**
	 * 解析文件名
	 * 
	 * @throws Exception
	 */
	public void parseFileName() {
		try {
			String fileName = FileUtil.getFileName(this.rawName);
			String[] str = StringUtil.split(fileName, "_");
			if (str.length == 5) {
				this.tableId = str[1];
				this.timeSplit = Integer.parseInt(str[2]);
				this.currentDataTime = TimeUtil.getyyyyMMddHHmmDate(str[3]);
			}
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	@Override
	public boolean fieldValHandle(Field field, String str, Map<String, String> map) {
		return fieldValHandle(field, str, map, 0);
	}

	/**
	 * @param field
	 * @param str
	 * @param map
	 * @param index
	 * @return detailed realize of fieldValHandle(Field field, String str, Map<String,String> map)
	 */
	public boolean fieldValHandle(Field field, String str, Map<String, String> map, int index) {
		if (field == null)
			return true;
		if (!"true".equals(field.getIsSplit()))
			return true;

		String regex = field.getRegex();
		int regexsNum = 0;
		// 处理多个表达式的情况
		if (field.getHasOtherRegexs() != null && "yes".equals(field.getHasOtherRegexs())) {
			regexsNum = field.getRegexsNum();
			String regexsSplitSign = field.getRegexsSplitSign();
			String[] regexArray = StringUtil.split(regex, regexsSplitSign);
			if (index < regexsNum && index < regexArray.length) {
				regex = regexArray[index];
			} else {
				badWriter.debug("拆分失败，" + field.getName() + ":" + str);
				return false;
			}
		}
		// 分拆字段列表
		List<Field> fieldList = field.getSubFieldList();
		// 表达式分拆
		String[] valList = StringUtil.split(regex, "?");
		int start = 0;
		for (int n = 0; n < fieldList.size() && n < (valList.length - 1); n++) {
			Field f = fieldList.get(n);
			int end = 0;
			if (n + 1 == fieldList.size()) {
				end = str.length();
			} else {
				end = str.indexOf(valList[n + 1]);
			}
			// 为""，并且是到最后了，赋整个长度
			if (end == 0 && n == valList.length - 2) {
				end = str.length();
			}
			// 第一次就匹配不上表达式，被认为是脏数据
			if (n == 0 && end == -1) {
				if (regexsNum > 0) {
					fieldValHandle(field, str, map, ++index);
					return true;
				} else {
					badWriter.debug("拆分失败，" + field.getName() + ":" + str);
					return false;
				}
			}
			// 不是第一次匹配不上，被认为是少了个别字段的数据，不是脏数据
			if (n != 0 && end == -1) {
				end = str.length();
				map.put(f.getIndex(), str.substring(start + valList[n].length(), end));
				break;
			}

			try {
				// 拆分字段赋值
				map.put(f.getIndex(), str.substring(start + valList[n].length(), end));
			} catch (Exception e) {
				if (regexsNum == 0) {
					badWriter.debug("拆分失败，" + field.getName() + ":" + str);
					return false;
				} else {
					fieldValHandle(field, str, map, ++index);
				}
			}
			start = end;
		}
		return true;
	}
}
