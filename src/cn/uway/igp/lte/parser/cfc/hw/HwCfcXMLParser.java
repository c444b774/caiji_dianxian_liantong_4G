package cn.uway.igp.lte.parser.cfc.hw;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

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

/**
 * @author yuy
 * @date 2014.2.21
 * 
 *       lte 华为配置解码器(XML格式)
 * 
 */
public class HwCfcXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(HwCfcXMLParser.class);

	/**
	 * head 字段列
	 */
	public String keyLine;

	/**
	 * className
	 */
	public String className;

	/**
	 * xml流
	 */
	public XMLStreamReader reader = null;

	/**
	 * 存放一条数据
	 */
	public Map<String, String> resultMap = null;

	/**
	 * 是否找到了第一个MO节点，即整个文件的公共部分。
	 */
	public boolean isFindTopMO = false;

	public HwCfcXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();

		String data = getStreamString(inputStream);
		inputStream = getStringStream(data);
		reader = XMLInputFactory.newInstance().createXMLStreamReader(inputStream);
	}

	/**
	 * 将一个字符串转化为输入流
	 */
	public static InputStream getStringStream(String sInputString) {
		if (sInputString != null && !sInputString.trim().equals("")) {
			try {
				ByteArrayInputStream tInputStringStream = new ByteArrayInputStream(sInputString.getBytes());
				return tInputStringStream;
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		return null;
	}

	/**
	 * 将一个输入流转化为字符串
	 */
	public static String getStreamString(InputStream tInputStream) {
		if (tInputStream != null) {
			try {
				BufferedReader tBufferedReader = new BufferedReader(new InputStreamReader(tInputStream));
				StringBuffer tStringBuffer = new StringBuffer();
				String line = new String("");
				while ((line = tBufferedReader.readLine()) != null) {
					if (line.contains("<attr name=\"DaylightSaveInfo\">")) {
						continue;
					}
					tStringBuffer.append(line).append("\n");
				}
				return tStringBuffer.toString();
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		return null;
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		int type = -1;
		resultMap = new HashMap<String, String>();
		String lastClassName = null;
		String textValue = null;
		try {
			while (reader.hasNext()) {

				type = reader.next();

				String tagName = null;

				if (type == XMLStreamConstants.START_ELEMENT || type == XMLStreamConstants.END_ELEMENT)
					tagName = reader.getLocalName();

				if (tagName == null) {
					continue;
				}
				switch (type) {
					case XMLStreamConstants.START_ELEMENT :
						if (tagName.equalsIgnoreCase("MO")) {
							lastClassName = className;
							className = StringUtil.nvl(reader.getAttributeValue(null, "className"), "").toUpperCase();
							if (!isFindTopMO) {
								isFindTopMO = true;
								continue;
							}
							if (findMyTemplet(lastClassName)) {
								return true;
							}
						} else if (tagName.equalsIgnoreCase("attr")) {
							// 取attr节点中，name属性的值。
							String attrName = StringUtil.nvl(reader.getAttributeValue(null, "name"), "").toUpperCase();
							// 取attr节点中的文件内容。
							textValue = reader.getElementText();
							String attrContent = StringUtil.nvl(textValue, "");
							resultMap.put(attrName, attrContent);
						}
						break;

					default :
						break;
				}
			}
			// 最后一条数据
			if (resultMap.size() > 0 && findMyTemplet(className)) {
				return true;
			}
		} catch (XMLStreamException e) {
			this.cause = textValue + ":【华为配置XML解码】IO读文件发生异常：" + e.getMessage();

			LOGGER.debug(cause);
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
			map.put(field.getIndex(), value);
		}

		// 公共回填字段
		map.put("MME_ID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		handleTime(map);
		record.setType(templet.getDataType());
		record.setRecord(map);
		return record;
	}

	/**
	 * 找到当前对应的Templet
	 */
	public boolean findMyTemplet(String className) {
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
	public void parseFileName() {
		try {
			String fileName = FileUtil.getFileName(this.rawName);
			String patternTime = StringUtil.getPattern(fileName, "\\d{8}");
			this.currentDataTime = getDateTime(patternTime, "yyyyMMdd");
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	// 将时间转换成format格式的Date
	public final Date getDateTime(String date, String format) {
		if (date == null) {
			return null;
		}
		if (format == null) {
			format = "yyyy-MM-dd HH:mm:ss";
		}
		try {
			DateFormat df = new SimpleDateFormat(format);
			return df.parse(date);
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();

		LOGGER.debug("[{}]-华为参数XML解析，处理{}条记录", new Object[]{task.getId(), readLineNum});
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
