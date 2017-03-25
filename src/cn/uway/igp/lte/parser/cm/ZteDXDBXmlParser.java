package cn.uway.igp.lte.parser.cm;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * @author weiw 2014.11.10 lte 中兴 电信单板参数性能解码器 需求 ： LTE中兴参数采集需求_V1.13_20141016.xlsx INVENTORY_L 表采集
 */
public class ZteDXDBXmlParser extends FileParser {

	public static final Logger LOGGER = LoggerFactory.getLogger(ZteDXDBXmlParser.class);

	public static String myName = "中兴LTE单板参数解析(XML)";

	public static String stampTime = "";

	public XMLStreamReader reader = null;

	/** 输入zip流 */
	public ZipInputStream zipstream;

	public ZipEntry entry = null;

	/** 结果map */
	public Map<String, String> resultMap = new HashMap<String, String>();
	
	private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

	public ZteDXDBXmlParser() {
	}

	public ZteDXDBXmlParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		LOGGER.debug("进入中兴单板解析类");

		this.accessOutObject = accessOutObject;

		this.before();
		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();

		zipstream = new ZipInputStream(inputStream);
		reader = getNextReader();

		if (reader == null) {
			LOGGER.debug(accessOutObject.getRawAccessName() + "中没有获取到有效的采集文件，采集退出");
			return;
		}
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		try {
			int type = -1;
			String tagName = null;
			boolean openFlag = false;
			while (reader != null && reader.hasNext()) {
				try {
					type = reader.next();
				} catch (XMLStreamException e) {
					LOGGER.info(entry.getName() + "文件解析发上异常，厂家文件生成错误！");
					continue;
				} catch (Exception e) {
					LOGGER.info(entry.getName() + "文件解析发上异常，厂家文件生成错误！");
					continue;
				}
				if (type == XMLStreamConstants.START_ELEMENT || type == XMLStreamConstants.END_ELEMENT)
					tagName = reader.getLocalName();
				if (tagName == null) {
					continue;
				}

				switch (type) {
					case XMLStreamConstants.START_ELEMENT :
						String name = reader.getLocalName();
						if (name.equalsIgnoreCase("vsDatainventory")) {
							openFlag = true;
							continue;
						}
						if (openFlag) {
							String value = reader.getElementText();
							if (name.equalsIgnoreCase("NE")) {
								if (value != null) {
									doParserTagOfNE(value);
								}
							} else {
								resultMap.put(name.toUpperCase(), value);
							}
						}
						break;
					case XMLStreamConstants.END_ELEMENT :
						String endTag = reader.getLocalName();
						if (endTag.equalsIgnoreCase("vsDatainventory")) {
							openFlag = false;
							return true;
						}
					default :
						break;
				}
			}
			reader = getNextReader();
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
		boolean flag = false;
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
			// 字段值处理
			if (!fieldValHandle(field, value, map)) {
				return null;
			}
			
			if("DATEOFMANUFACTURE".equals(field.getName().toUpperCase()) 
					|| "DATEOFLASTSERVICE".equals(field.getName().toUpperCase()))
			{
				try{
					if(!"".equals(value.trim()))
					{
						// 格式化异常的2012-01-00数据
						if(!value.matches("\\d{4}-\\d{2}-\\d{2}"))
						{
							flag = true;
							break;
						}
						Date date = sdf.parse(value);
						value = sdf.format(date);
					}
				}
				catch(Exception e)
				{
					LOGGER.debug(field.getName()+" is error format date ,value = "+value);
					flag = true;
					break;
				}
			}
			map.put(field.getIndex(), value);
		}

		// 公共回填字段
		map.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));

		handleTime(map);
		record.setType(templet.getDataType());
		record.setRecord(map);
		resultMap.clear();
		// 作为非法记录处理
		if(flag)
		{
			return null;
		}
		return record;
	}

	/**
	 * 获取下一个reader
	 * 
	 * @return
	 * @throws Exception
	 */
	public XMLStreamReader getNextReader() throws Exception {
		while ((entry = zipstream.getNextEntry()) != null) {
			/** 对厂家文件进行过滤，因为厂家文件可能存在生成失败的情况而导致采集停滞 */
			if (!entry.getName().endsWith(".xml")) {
				LOGGER.warn(entry.getName() + "厂家文件错误，跳过该文件！");
				continue;
			}
			if ((templet = findTemplet(entry.getName())) == null) {
				continue;
			}
			XMLInputFactory fac = XMLInputFactory.newInstance();
			fac.setProperty("javax.xml.stream.supportDTD", false);
			XMLStreamReader reader = fac.createXMLStreamReader(zipstream);
			return reader;
		}
		return null;
	}

	/**
	 * NE结点字段分为 SUBNETWORK 和 MEID 两个采集字段
	 * 
	 * @param tagValue
	 */
	private void doParserTagOfNE(String tagValue) {
		resultMap.put("NE", tagValue);
		if (StringUtil.isEmpty(tagValue))
			return;
		String[] array = StringUtil.split(tagValue, ",");
		StringBuffer neTypeNames = new StringBuffer();
		for (int n = 0; n < array.length; n++) {
			String str = array[n];
			String[] keyValue = StringUtil.split(str, "=");
			neTypeNames.append((n == 0 ? "" : ",") + keyValue[0]);
			if (keyValue.length == 2)
				resultMap.put(keyValue[0].toUpperCase(), keyValue[1]);
		}
		resultMap.put("NE_TYPE_NAME", neTypeNames.toString());
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
			if (patternTime == null) {
				patternTime = StringUtil.getPattern(fileName, "\\d{4}[-]\\d{2}[-]\\d{2}");
			}
			if (patternTime != null) {
				patternTime = patternTime.replace("-", "");
				this.currentDataTime = TimeUtil.getyyyyMMddDate(patternTime);
			}
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();

		LOGGER.debug("[{}]-{}，处理{}条记录", new Object[]{task.getId(), myName, readLineNum});
	}

	/**
	 * 功能描述：从模板中查询对应被解析文件的模板
	 * 
	 * @author guom
	 * @param 解析文件名
	 * */
	private Templet findTemplet(String fileName) throws Exception {
		String tmpFileName = "";
		Templet templetTemp = null;
		if ("".equals(fileName) || null == fileName) {
			return null;
		}
		Set<String> mapSet = templetMap.keySet();
		for (String str : mapSet) {
			tmpFileName = str.substring(str.indexOf("*") + 1, str.length() - 5);
			if (fileName.contains(tmpFileName)) {
				templetTemp = templetMap.get(str);
				return templetTemp;
			}
		}
		return null;
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		// 解析模板
		TempletParser templetParser = new TempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();
		templetMap = templetParser.getTemplets();
	}

}
