package cn.uway.igp.lte.parser.pm.alu;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.igp.lte.templet.xml.LucPmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * lte 朗讯性能解码器(类XML)
 * 
 * @author yuy @ 26 May, 2014
 */
public class LucPmZipXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory
			.getLogger(LucPmXMLParser.class);

	public static String myName = "朗讯性能解析(类XML)";

	public XMLStreamReader reader;

	/** 结果map */
	public Map<String, String> resultMap = null;

	/** counter名,mt列表 */
	public List<String> currMT;

	/** 读取开关 */
	public boolean on_off = false;

	/** 输入zip流 */
	public ZipInputStream zipstream;

	public ZipEntry entry = null;

	public LucPmZipXMLParser() {

	}

	/**
	 * 模板传入接口
	 * 
	 * @param tmpfilename
	 */
	public LucPmZipXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();

		zipstream = new ZipInputStream(inputStream);
		entry = zipstream.getNextEntry();
		if (entry == null){
			return;
		}
		/**对应厂家错误文件不予处理，跳过*/
		if(entry.getName().endsWith(".gz"))	
		{
			return;
		}
		setCurrentDataTime(entry.getName());
		XMLInputFactory fac = XMLInputFactory.newInstance();
		fac.setProperty("javax.xml.stream.supportDTD", false);
		reader = fac.createXMLStreamReader(zipstream);
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		resultMap = new HashMap<String, String>();
		try {
			/* type记录stax解析器每次读到的对象类型，是element，还是attribute等等…… */
			int type = -1;
			/* 保存当前的xml标签名 */
			String tagName = null;
			/* 当前r列表 */
			List<String> currR = new ArrayList<String>();
			// moid 数据唯一标识
			String moid = null;
			/* 开始迭代读取xml文件 */
			while (reader.hasNext()) {
				try {
					type = reader.next();
				} catch (XMLStreamException e) {
					LOGGER.info(entry.getName() + "阿朗文件解析发上异常，厂家文件生成错误！");
					continue;
				} catch (Exception e) {
					LOGGER.info(entry.getName() + "阿朗文件解析发上异常，厂家文件生成错误！");
					continue;
				}
				if (type == XMLStreamConstants.START_ELEMENT
						|| type == XMLStreamConstants.END_ELEMENT)
					tagName = reader.getLocalName();
				if (tagName == null) {
					continue;
				}
				switch (type) {
				case XMLStreamConstants.START_ELEMENT:
					if (tagName.equalsIgnoreCase("r") && on_off) {
						/** 处理r标签，读取counter值 */
						currR.add(StringUtil.nvl(reader.getElementText(), "")
								.toUpperCase());
					} else if (tagName.equalsIgnoreCase("mt")) {
						if (currMT == null)
							currMT = new ArrayList<String>();
						/** 处理mt标签，读取counter名 */
						currMT.add(StringUtil.nvl(reader.getElementText(), "")
								.toUpperCase());
					} else if (tagName.equalsIgnoreCase("moid")) {
						moid = StringUtil.nvl(reader.getElementText(), "");
						String[] array = StringUtil.split(moid, ",");
						if (array != null && array.length > 0) {
							String tmp = array[array.length - 1];
							// moid中最后一个属性名
							String name = tmp.substring(0, tmp.indexOf("="));
							name = name.trim();
							if (findMyTemplet(name)) {
								/** 读取开关开启 */
								on_off = true;
							}
							break;
						}
					}
					break;
				case XMLStreamConstants.END_ELEMENT:
					/** 遇到mv结束标签，应处理并清空r列表和当前moid */
					if (tagName.equalsIgnoreCase("mv")) {
						if (currMT != null && currR != null && on_off) {
							for (int n = 0; n < currMT.size()
									&& n < currR.size(); n++) {
								resultMap.put(currMT.get(n).toUpperCase(),
										currR.get(n));
							}
							resultMap.put("MOID", moid);
							/** 读完开关关闭 */
							on_off = false;
							currR = null;
							return true;
						}
					}
					/** 遇到mts结束标签，应处理并清空mt列表 */
					else if (tagName.equals("mts")) {
						currMT = null;
					}
					else if(tagName.equals("mdc"))
					{
						
					}
					break;
				default:
					break;
				}
			}
			while ((entry = zipstream.getNextEntry()) != null) {
				/** 释放之前的reader资源 */
				if (reader != null) {
					reader.close();
				}
				if(entry.getName().endsWith(".gz"))	
				{
					continue;
				}
				setCurrentDataTime(entry.getName());
				XMLInputFactory fac = XMLInputFactory.newInstance();
				fac.setProperty("javax.xml.stream.supportDTD", false);
				reader = fac.createXMLStreamReader(zipstream);
				return hasNextRecord();
			}

		} catch (Exception e) {
			this.cause = "【" + myName + "】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		readLineNum++;
		ParseOutRecord record = new ParseOutRecord();
		List<Field> fieldList = templet.getFieldList();
		Map<String, String> map = this.createExportPropertyMap(templet
				.getDataType());
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

			// 字段值处理
			if (!fieldValHandle(field, value, map)) {
				return null;
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
	 * 找到当前对应的Templet
	 */
	public boolean findMyTemplet(String moid) {
		templet = templetMap.get(moid);// 这里的key全部转为大写字母
		if (templet == null) {
			LOGGER.debug("没有找到对应的模板，跳过，moid:{}", moid);
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
			String patternTime = StringUtil.getPattern(fileName,
					"\\d{8}[.]\\d{4}");
			if (patternTime != null) {
				patternTime = patternTime.replace(".", "_");
				this.currentDataTime = TimeUtil
						.getyyyyMMdd_HHmmDate(patternTime);
			}
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	@Override
	public void close() {

		try {
			if (reader != null) {
				reader.close();
			}

			if (inputStream != null) {
				inputStream.close();
			}
			if (zipstream != null) {
				zipstream.close();
			}
		} catch (XMLStreamException e) {
			LOGGER.warn("阿朗性能文件解析完成之后关闭文件流异常！");
		} catch (IOException e) {
			LOGGER.warn("阿朗性能文件解析完成之后关闭文件流异常！");
		}
		// 标记解析结束时间
		this.endTime = new Date();

		LOGGER.debug("[{}]-{}，处理{}条记录", new Object[] { task.getId(), myName,
				readLineNum });
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		// 解析模板
		TempletParser templetParser = new LucPmXmlTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();

		templetMap = templetParser.getTemplets();
	}

}