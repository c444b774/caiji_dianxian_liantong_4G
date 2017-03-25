package cn.uway.igp.lte.parser.cfc.hw;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.file.CSVParser;
import cn.uway.framework.parser.file.templet.CSVCfcTempletParser;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * @author yuy 2014.1.6 lte 华为配置解码器
 */
public class HwCfcCSVParser extends CSVParser {

	private static Logger LOGGER = LoggerFactory.getLogger(HwCfcCSVParser.class);

	/**
	 * head 字段列
	 */
	public String keyLine;

	/**
	 * className
	 */
	public String className;

	public HwCfcCSVParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();

		reader = new BufferedReader(new InputStreamReader(inputStream, "GBK"), 16 * 1024);
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		String line = null;
		String keys = null;
		String values = null;
		try {
			while ((line = reader.readLine()) != null) {
				if (keys != null && values != null) {
					break;
				}
				line = line.trim();
				if (line.length() == 0) {
					continue;
				}
				if (keys == null) {
					keys = line;
				} else if (values == null) {
					values = line;
				} else {
					break;
				}
			}
			keyLine = keys;
			currentLine = values;
			if (keyLine == null && currentLine == null) {
				return false;
			}
			// 字段定位
			setFieldLocalMap(keyLine);
			// 找到对应的模板 找不到的跳过
			if (!findMyTemplet()) {
				if (!hasNextRecord()) {
					return false;
				}
			}
		} catch (IOException e) {
			this.cause = "【华为配置CSV解码】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
		return true;
	}

	/**
	 * 找到当前对应的Templet
	 */
	public boolean findMyTemplet() {
		String tmpLine = switchLine(currentLine, splitSign);
		String[] valuesArray = StringUtil.split(tmpLine, splitSign);
		String className = valuesArray[fieldLocalMap.get("CLASSNAME")];
		templet = templetMap.get(className);// 这里的key全部转为大写字母

		if (templet == null) {
			// throw new NullPointerException("没有找到对应的模板，解码退出");
			LOGGER.debug("没有找到对应的模板，跳过，classname:{}", className);
			return false;
		}
		return true;
	}

	/**
	 * 解析文件名
	 * 
	 * @throws Exception
	 */
	@SuppressWarnings("deprecation")
	public void parseFileName() {
		try {
			String fileName = FileUtil.getFileName(this.rawName);
			String[] str = StringUtil.split(fileName, "_");
			this.currentDataTime = TimeUtil.getyyyyMMddHHDate(str[str.length - 1]);
			this.currentDataTime.setMinutes(0);
			this.currentDataTime.setSeconds(0);
			this.currentDataTime.setHours(0);
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
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
}
