package cn.uway.igp.lte.parser.pm.fh;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.igp.lte.templet.xml.FhPmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * lte 烽火(fh)性能解码器(xml格式)
 * 
 * @author yuy @ 29 August, 2014
 */
public class FhPmXMLParser extends FileParser {

	/** 日志记录器 */
	private static Logger LOGGER = LoggerFactory.getLogger(FhPmXMLParser.class);

	/** 解析器名称 */
	public static String myName = "烽火性能XML解析";

	/** stax阅读器 */
	public XMLStreamReader reader;

	/** 所有文件数据map */
	public Map<String, Map<String, Map<String, String>>> allDataMap = null;

	/** 当前文件数据map */
	public Map<String, Map<String, String>> currFileDataMap = null;

	/** 当前网元数据map */
	public Map<String, String> currMeasDataMap = null;

	/** 当前数据类型，elementType */
	public String currElementType = null;

	/** elementType数组 */
	public Object[] typeArray = null;

	/** 网元数组 */
	public Object[] measArray = null;

	/** elementType数组索引 */
	public int typeIndex = 0;

	/** 网元数组索引 */
	public int measIndex = 0;

	/** 读取开关 */
	public boolean on_off = false;

	/** <job jobId="55016">，同文件名中的55016 */
	public String jobId;

	/** 输入zip流 */
	public ZipInputStream zipstream;

	/** zip.entry，压缩实例 */
	public ZipEntry entry = null;

	/** 数据类型 */
	public String elementType;

	public FhPmXMLParser() {

	}

	/**
	 * 模板传入接口
	 * 
	 * @param tmpfilename
	 */
	public FhPmXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();

		/* 创建STAX解析器 */
		XMLInputFactory fac = XMLInputFactory.newInstance();
		fac.setProperty("javax.xml.stream.supportDTD", true);

		if (accessOutObject.getRawAccessName().endsWith(".zip")) {
			zipstream = new ZipInputStream(inputStream);
			entry = zipstream.getNextEntry();
			if (entry == null) {
				return;
			}
			reader = XMLInputFactory.newInstance().createXMLStreamReader(zipstream);
		} else {
			reader = XMLInputFactory.newInstance().createXMLStreamReader(inputStream);
		}

		if (allDataMap == null) {
			allDataMap = new HashMap<String, Map<String, Map<String, String>>>();
		}
		/* 解析单个文件 */
		parseFile();
	}

	/**
	 * 解析单个文件
	 * 
	 * @return
	 * @throws Exception
	 */
	public boolean parseFile() throws Exception {
		try {
			/* type记录stax解析器每次读到的对象类型，是element，还是attribute等等…… */
			int type = -1;
			/* 保存当前的xml标签名 */
			String tagName = null;
			/* counter名,mt的map，key值为p属性值，value为标签内容 */
			Map<String,String> currMT = null;
			/* 当前r列表 key=p,value=text*/
			Map<String,String> currR = null;
			/* measObjLdn 数据唯一标识 */
			String measObjLdn = null;
			/* 当前解析文件的所有数据 */
			Map<String, Map<String, String>> currFileDataMap_ = null;
			/* 当前measObjLdn的所有数据 */
			Map<String, String> currMeasDataMap_ = null;
			/* 开始迭代读取xml文件 */
			while (reader.hasNext()) {
				type = reader.next();
				if (type == XMLStreamConstants.START_ELEMENT || type == XMLStreamConstants.END_ELEMENT)
					tagName = reader.getLocalName();
				if (tagName == null) {
					continue;
				}
				switch (type) {
					case XMLStreamConstants.START_ELEMENT :
						if (tagName.equalsIgnoreCase("r") && on_off) {
							/** 处理r标签，读取counter值 */
							currR.put(reader.getAttributeValue("","p"), StringUtil.nvl(reader.getElementText(), ""));
						} else if (tagName.equalsIgnoreCase("measType")) {
							if (currMT == null)
								currMT = new HashMap<String,String>();
							/** 处理mt标签，读取counter名 */
							currMT.put(reader.getAttributeValue("","p"),StringUtil.nvl(reader.getElementText(), ""));
						} else if (tagName.equalsIgnoreCase("measValue")) {
							currR = new HashMap<String,String>();
							measObjLdn = StringUtil.nvl(reader.getAttributeValue(null, "measObjLdn"), "");
							currMeasDataMap_ = currFileDataMap_.get(measObjLdn);
							if (currMeasDataMap_ == null) {
								currMeasDataMap_ = new HashMap<String, String>();
								currFileDataMap_.put(measObjLdn, currMeasDataMap_);
							}
							break;
						} else if (tagName.equalsIgnoreCase("measInfo")) {
							if (findMyTemplet(elementType)) {
								/** 读取开关开启 */
								on_off = true;
								currFileDataMap_ = allDataMap.get(elementType);
								if (currFileDataMap_ == null) {
									currFileDataMap_ = new HashMap<String, Map<String, String>>();
									allDataMap.put(elementType, currFileDataMap_);
								}
								continue;
							}
							return false;
						} else if (tagName.equalsIgnoreCase("fileSender")) {
							elementType = StringUtil.nvl(reader.getAttributeValue(null, "elementType"), "");
						} else if (tagName.equalsIgnoreCase("job")) {
							jobId = StringUtil.nvl(reader.getAttributeValue(null, "jobId"), "");
						}
						break;
					case XMLStreamConstants.END_ELEMENT :
						/** 遇到mv结束标签，应处理并清空r列表和当前moid */
						if (tagName.equalsIgnoreCase("measValue")) {
							if (currMT != null && currR != null && on_off) {
								Iterator<String> it = currR.keySet().iterator();
								String key = null;
								while(it.hasNext())
								{
									key = it.next();
									currMeasDataMap_.put(currMT.get(key).toUpperCase(), currR.get(key));
								}
								String tmpJobid = StringUtil.nvl(currMeasDataMap_.get("JOBID"), "");
								if (tmpJobid.indexOf(jobId) == -1) {
									currMeasDataMap_.put("JOBID", (tmpJobid.equals("") ? "" : (tmpJobid + ",")) + jobId);
								}
								currMeasDataMap_.put("MEASOBJLDN", measObjLdn);
								// currMeasDataMap_.put("FILE_NAME", accessOutObject.getRawAccessName());
								currR = null;
								continue;
							}
						}
						/** 遇到mts结束标签，应处理并清空mt列表 */
						else if (tagName.equals("measInfo") && on_off) {
							/** 读完开关关闭 */
							on_off = false;
							currMT = null;
						}
						break;
					default :
						break;
				}
			}
			String name="";
			if(entry!=null)
				name = entry.getName();
			else
				name = accessOutObject.getRawAccessName();
			LOGGER.debug("[{}]-[{}]-[{}]，合并累计解析{}条记录", new Object[]{task.getId(), myName, name, currFileDataMap_.size()});
//			while (zipstream != null && (entry = zipstream.getNextEntry()) != null) {
//				/** 释放之前的reader资源 */
//				if (reader != null) {
//					reader.close();
//				}
//				XMLInputFactory fac = XMLInputFactory.newInstance();
//				fac.setProperty("javax.xml.stream.supportDTD", true);
//				reader = fac.createXMLStreamReader(zipstream);
//				return parseFile();
//			}
		} catch (Exception e) {
			this.cause = "【" + myName + "】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
		return false;
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		if (allDataMap.isEmpty())
			return false;
		if (typeArray == null) {
			typeArray = allDataMap.keySet().toArray();
		}
		// 第一次初始化；当读到一个map末尾时
		if (currFileDataMap == null || (measIndex >= measArray.length && typeIndex < typeArray.length)) {
			currElementType = (String) typeArray[typeIndex++];
			currFileDataMap = allDataMap.get(currElementType);
			measArray = currFileDataMap.keySet().toArray();
			// 归零
			measIndex = 0;
		}
		if (currFileDataMap != null && measIndex < measArray.length) {
			currMeasDataMap = currFileDataMap.get(measArray[measIndex++]);
			return true;
		}
		allDataMap = null;
		typeArray = null;
		currFileDataMap = null;
		currElementType = null;
		currMeasDataMap = null;
		measIndex = 0;
		typeIndex = 0;
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		readLineNum++;
		ParseOutRecord record = new ParseOutRecord();
		List<Field> fieldList = templetMap.get(currElementType).getFieldList();
		Map<String, String> map = new HashMap<String, String>();
		for (Field field : fieldList) {
			if (field == null) {
				continue;
			}
			String value = currMeasDataMap.get(field.getName().toUpperCase());
			// 找不到，设置为空
			if (value == null) {
				map.put(field.getIndex(), "");
				continue;
			}

			// 字段值处理
			if (!fieldValHandle(field,value, map)) {
				return null;
			}
			//6进制转换成10进制
			if("MEASOBJLDN".equals(field.getName().toUpperCase()))
			{
				if(map.get("ManagedElement") != null)
				{
					map.get("InventoryUnitPack");
					String managedElement = map.get("ManagedElement");
					if(managedElement.indexOf(",") > 0)
					{
						managedElement = managedElement.substring(0, managedElement.indexOf(","));
					}
					map.put("ManagedElement",String.valueOf(Long.parseLong(managedElement, 16)));
				}
				if(map.get("EnbFunction") != null)
				{
					map.put("EnbFunction",String.valueOf(Long.parseLong(map.get("EnbFunction"), 16)));
				}
				if(map.get("EutranCellTdd") != null)
				{
					map.put("EutranCellTdd",String.valueOf(Long.parseLong(map.get("EutranCellTdd"), 16)));
				}
			}
						
			map.put(field.getIndex(), value);
		}

		// 公共回填字段
		map.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		handleTime(map);
		record.setType(templetMap.get(currElementType).getDataType());
		record.setRecord(map);
		return record;
	}

	/**
	 * 找到当前对应的Templet
	 */
	public boolean findMyTemplet(String elementType) {
		if (elementType == null)
			return false;

		templet = templetMap.get(elementType);// 这里的key全部转为大写字母
		if (templet == null) {
			LOGGER.debug("没有找到对应的模板，跳过，elementType:{}", elementType);
			return false;
		}
		return true;
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();

		LOGGER.debug("[{}]-{}，处理{}条记录", new Object[]{task.getId(), myName, readLineNum});
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		if (templetMap != null)
			return;
		// 解析模板
		TempletParser templetParser = new FhPmXmlTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();

		templetMap = templetParser.getTemplets();
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
			this.currentDataTime = getDateTime(patternTime, "yyyyMMddHHmm");
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
}
