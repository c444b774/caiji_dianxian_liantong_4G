package cn.uway.igp.lte.parser.pm.eric;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
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
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.igp.lte.templet.xml.EricPmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * lte eric性能解码器(xml格式)
 * 
 * @author yuy @ 24 May, 2014
 */
public class EricPmZipXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(EricPmZipXMLParser.class);

	public static String myName = "";

	public XMLStreamReader reader;

	/** 输入zip流 */
	public ZipInputStream zipstream;

	/** 读取开关 */
	public boolean openFlag = false;

	/**
	 * 缓存中的元素
	 */
	private Map<MoElement, ParseOutRecord> temps;

	/**
	 * 当前正在解析的Mo对象
	 */
	private MoElement currentMoElement = null;

	/**
	 * 公共数据域信息，解析自SN。每解析一个文件清空一次
	 */
	private Map<String, String> commonFields = new HashMap<String, String>();

	/**
	 * 缓存对象
	 */
	private List<ParseOutRecord> cacheElements = new LinkedList<ParseOutRecord>();

	/**
	 * 当前解析的MT节点缓存
	 */
	private List<String> currentFields = new ArrayList<String>();

	/**
	 * 当前缓存中的MT对应的Value,即r节点
	 */
	private List<String> currentValues = new ArrayList<String>();

	public ZipEntry entry = null;

	/** 数据源是否来源于ENIQ库 */
	public boolean isFromENIQ = false;

	/** ENIQ库--模板标识，不同于非ENIQ库 */
	public String ENIQ_TEMPLET_FLAG = "_ENIQ";
	
	/** 输入流 */
	protected InputStream rawFileStream;
	
	private String entryName = null;

	public EricPmZipXMLParser() {
		super();
	}

	/**
	 * 模板传入接口
	 * 
	 * @param tmpfilename
	 */
	public EricPmZipXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.before();
		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());
		// 解析模板 获取当前文件对应的templet
		parseTemplet();
		if(accessOutObject.getRawAccessName() != null 
				&& accessOutObject.getRawAccessName().endsWith(".zip"))
		{
			rawFileStream = new ZipInputStream(inputStream);
		}
		else
		{
			rawFileStream = inputStream;
		}
		//tyler2016-7-29,解决文件格式有问题，只有<mt>，<r>标签时，下面集合会有值的问题；
		currentFields.clear();
		currentValues.clear();
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		// 如果缓存中有元素，则取缓存中的元素
		if (cacheElements.size() > 0)
			return true;
		if(rawFileStream instanceof ZipInputStream)
		{
			entry = ((ZipInputStream)rawFileStream).getNextEntry();
			if (entry == null)
				return false;
			entryName = entry.getName();
		}
		else
		{
			entryName = accessOutObject.getRawAccessName();
		}
		
		setCurrentDataTime(entryName);
		// 判断是否是ENIQ库的数据源，文件名如：*_osscounterfile_1.xml.gz
		if (entryName.indexOf("osscounterfile") > -1)
			isFromENIQ = true;
		// TODO 硬编码 过滤TDD
		// if(entry.getName().indexOf("TBJ") > 0)
		// return hasNextRecord();
		XMLInputFactory fac = XMLInputFactory.newInstance();
		fac.setProperty("javax.xml.stream.supportDTD", false);
		reader = fac.createXMLStreamReader(rawFileStream);
		// 开始解码前，清空每个文件的公共消息
		commonFields = new HashMap<String, String>();
		parseNextFile();
		// 递归，避免中间有一个空文件导致数据异常
		return cacheElements.size() > 0 ? true : false;
	}

	public ParseOutRecord nextRecord() throws Exception {
		if (cacheElements == null || cacheElements.size() <= 0)
			return null;
		readLineNum++;
		ParseOutRecord outElement = cacheElements.remove(0);
		// 将公共信息添加进入
		Map<String, String> data = outElement.getRecord();
		outElement.getRecord().putAll(commonFields);
		// 添加MMEID、COLLECTTIME、STAMPTIME等字段
		data.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		data.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		handleTime(data);
		return outElement;
	}

	/**
	 * 解析压缩包内的单个文件<br>
	 * 一、需要解析的数据域：<br>
	 * 1、SN：数据解析后是文件的全局数据<br>
	 * 2、moid：标记具体的网元对象<br>
	 * 3、mt：测量项<br>
	 * 4、r：测量项的值<br>
	 * 二、需要解析的结束标记<br>
	 * 1、mv：标记一段测量消息的结束<br>
	 * 2、mi：标记一种测量类型的结束<br>
	 * 
	 * @throws XMLStreamException
	 */
	private void parseNextFile() throws XMLStreamException {
		temps = new HashMap<MoElement, ParseOutRecord>();
		while (reader.hasNext()) {
			TagElement element = getNextValidTag();
			if (element == null)
				break;
			// 结束的TAG，当tag时mv时，需要处理字段的值
			if (element.isEnd()) {
				handleEndElement(element);
				continue;
			}
			handleBeginElement(element);
		}
		// 解析完成之后，进行缓存数据的处理
		if (temps.size() <= 0)
			return;
		handle();
	}

	/**
	 * 根据模板解析单个文件的解码结果
	 */
	private void handle() {
		Set<MoElement> moElements = temps.keySet();
		outer : for (MoElement moElement : moElements) {
			ParseOutRecord outElement = temps.get(moElement);
			Map<String, String> data = outElement.getRecord();
			findTemplate(moElement);
			if (templet == null)
				continue;
			List<Field> fields = templet.getFieldList();
			inner : for (Field field : fields) {
				if (field == null)
					continue inner;
				String value = data.get(field.getName().toUpperCase());
				if (value == null) {
					data.put(field.getIndex(), "");
					continue inner;
				}
				if (!fieldValHandle(field, value, data))
					continue outer;
				data.put(field.getIndex(), value);
			}
			this.cacheElements.add(outElement);
		}
	}

	/**
	 * 处理开始节点事件，只需要在关注的数据域事件：<br>
	 * 1、SN：数据解析后是文件的全局数据<br>
	 * 2、moid：标记具体的网元对象<br>
	 * 3、mt：测量项<br>
	 * 4、r：测量项的值<br>
	 * 
	 * @param element
	 * @throws XMLStreamException
	 */
	private void handleBeginElement(TagElement element) throws XMLStreamException {
		if (element.getName().equalsIgnoreCase("mt")) {
			currentFields.add(StringUtil.nvl(reader.getElementText(), ""));
		} else if (element.getName().equalsIgnoreCase("r") && openFlag) {
			currentValues.add(StringUtil.nvl(reader.getElementText(), ""));
		} else if (element.getName().equalsIgnoreCase("moid")) {
			String moid = StringUtil.nvl(reader.getElementText(), "");
			String[] moInfo = StringUtil.split(moid, ",");
			List<String[]> moArrayList = new ArrayList<String[]>();
			for (String str : moInfo) {
				String[] arr = StringUtil.split(str, "=");
				moArrayList.add(arr);
			}
			String temp = moInfo[moInfo.length - 1];
			String moType = temp.substring(0, temp.indexOf("="));
			String moValue = temp.substring(temp.indexOf("=") + 1);
			currentMoElement = new MoElement(moType, moArrayList);
			// 如果没找到模板,则当前MO不用解析
			if (!findTemplate(currentMoElement)) {
				openFlag = false;
				return;
			}
			openFlag = true;
			ParseOutRecord outElement = temps.get(currentMoElement);
			if (outElement == null) {
				outElement = new ParseOutRecord();
				outElement.setType(this.templet.getDataType());
				Map<String, String> data = this.createExportPropertyMap(templet.getDataType());
				data.put(moType.toUpperCase(), moValue);
				data.put("MOID", moid);
				outElement.setRecord(data);
				temps.put(currentMoElement, outElement);
			}
		} else if (element.getName().equalsIgnoreCase("sn")) {
			List<String[]> list = parseSN(reader.getElementText());
			if (list == null || list.size() == 0) {
				//修改 从entry 对象中取entryName 为直接使用entryName
				//因为entry在<sn></sn>标签内容为空时它也为空 
				String fileName = FileUtil.getFileName(entryName);
				list = parseSN(fileName.substring(fileName.indexOf("_SubNetwork") + 1, fileName.lastIndexOf("_statsfile")));
			}
			commonFields.put("SUBNETWORK_ROOT", findByName(list, "SubNetworkRoot"));
			commonFields.put("SUBNETWORK", findByName(list, "SubNetwork"));
			commonFields.put("MECONTEXT", findByName(list, "MeContext"));
		}
	}

	/**
	 * 处理结束节点<br>
	 * 1、当碰到mv节点结束的时候，处理缓存的MT和R节点的值，添加至输出对象中<br>
	 * 2、1处理完成后，清空currentValues<br>
	 * 3、碰到mi节点，清理currentFields
	 * 
	 * @param element
	 *            节点信息
	 */
	private void handleEndElement(TagElement element) {
		if ("mi".equalsIgnoreCase(element.getName())) {
			currentFields = new ArrayList<String>();
			return;
		}
		if (!element.getName().equalsIgnoreCase("mv"))
			return;
		// 没有打开标记，即没有找到模板，跳过
		if (!openFlag)
			return;
		ParseOutRecord parseOutRecord = temps.get(currentMoElement);
		if (parseOutRecord == null) {
			throw new NullPointerException();
		}
		int size = currentFields.size();
		if (size > 0 && currentFields.size() != currentValues.size())
			throw new RuntimeException("文件错误,MT个数和值的格式不匹配");
		Map<String, String> elements = parseOutRecord.getRecord();
		for (int i = 0; i < size; i++) {
			String value = currentValues.get(i);
			if (value == null || "".equals(value))
				continue;
			elements.put(currentFields.get(i).toUpperCase(), value);
		}
		currentValues = new ArrayList<String>();
	}

	/**
	 * 获取下一个有效的TAG名称和类型<br>
	 * 只有类型是开始或者结束，并且名称不为空的TAG，才认为是合法的<br>
	 * 
	 * @return 下一个有效的TAG
	 * @throws XMLStreamException
	 */
	private TagElement getNextValidTag() throws XMLStreamException {
		while (reader.hasNext()) {
			String tagName = null;
			try {
				int type = reader.next();
				if (type == XMLStreamConstants.START_ELEMENT || type == XMLStreamConstants.END_ELEMENT)
					tagName = reader.getLocalName();
				if (tagName != null)
					return new TagElement(type, tagName);
			} catch (Exception e) {
				continue;
			}
		}
		return null;
	}

	/**
	 * 节点标签对象
	 * 
	 * @author chenrongqiang
	 * @since 1.0
	 * @version 1.0
	 * @date 2014年6月19日
	 */
	class TagElement {

		/**
		 * 节点类型
		 */
		private int type;

		/**
		 * 节点名称
		 */
		private String name;

		public TagElement(int type, String name) {
			super();
			this.type = type;
			this.name = name;
		}

		public int getType() {
			return type;
		}

		public String getName() {
			return name;
		}

		public boolean isBegin() {
			return type == XMLStreamConstants.START_ELEMENT;
		}

		public boolean isEnd() {
			return type == XMLStreamConstants.END_ELEMENT;
		}
	}

	/**
	 * 找到当前对应的Templet
	 */
	public boolean findTemplate(MoElement moElement) {
		String templetMoid = moElement.getType();
		if (isFromENIQ)
			templetMoid += ENIQ_TEMPLET_FLAG;
		templet = templetMap.get(templetMoid);// 这里的key全部转为大写字母
		if (templet == null && "EUtranCellTDD".equals(moElement.getType())) {
			templet = templetMap.get(isFromENIQ ? "EUtranCellFDD" + ENIQ_TEMPLET_FLAG : "EUtranCellFDD");
		}
		if (templet == null) {
			LOGGER.debug("没有找到对应的模板，跳过，moid:{}", templetMoid);
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
			String patternTime = StringUtil.getPattern(fileName, "\\d{8}[.]\\d{4}");
			if (patternTime != null) {
				patternTime = patternTime.replace(".", "_");
				this.currentDataTime = TimeUtil.getyyyyMMdd_HHmmDate(patternTime);
			}
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("[{}]-爱立信性能XML解析，处理{}条记录", new Object[]{task.getId(), readLineNum});
	}

	/**
	 * 解析模板 解析当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		// 解析模板
		TempletParser templetParser = new EricPmXmlTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();
		templetMap = templetParser.getTemplets();
	}

	/* 解sn标签内容 */
	public static List<String[]> parseSN(String moid) {
		return parseKeyValue(moid, true);
	}

	/*
	 * 解析键值对字符串，即sn和moid标签中的内容，并存入有序列表。列表中的对象，是String数组， [0]为key,[1]为value. 参数str为要解析的字符串，isSN表示是否解析的是sn标签，sn标签中有同名的key，要特别处理。
	 */
	private static List<String[]> parseKeyValue(String str, boolean isSN) {
		if (StringUtil.isEmpty(str))
			return null;
		List<String[]> list = new ArrayList<String[]>();
		String[] sp = StringUtil.split(str, ",");
		for (int i = 0; i < sp.length; i++) {
			if (!isSN) {
				list.add(StringUtil.split(sp[i], "="));
			} else {
				String[] entry = StringUtil.split(sp[i], "=");
				/*
				 * 处理sn标签内容 ，格式是 "SubNetwork=ONRM_ROOT_MO_R,SubNetwork=DGRNC01,MeContext=FG_BenCaoDaSha-_1502" 这样的，第一和第二个都是SubNetwork，第三个是MeContext
				 */
				switch (i) {
					case 0 :
						// 第一个SubNetwork改名为SubNetworkRoot
						entry[0] = "SubNetworkRoot";
						list.add(entry);
						break;
					case 1 :
						// 第二个SubNetwork，正常添加。
						list.add(entry);
						break;
					case 2 :
						// 第三个key，是MeContext，也是最后一个，这时，要添加一个RNC_NAME，就用第二个SubNetWork的值.
						list.add(entry);
						list.add(new String[]{"RNC_NAME", list.get(1)[1]});
						break;
					default :
						break;
				}

			}
		}
		return list;
	}

	/**
	 * 在List<String[]>中按key名查找value.
	 */
	public static String findByName(List<String[]> list, String name) {
		if (list == null || name == null)
			return "";
		for (String[] arr : list) {
			if (arr[0].equalsIgnoreCase(name))
				return arr[1];
		}
		return "";
	}
	
	/**
	 * 特殊分拆处理器,重写<code>{@link FileParser#specialSplitHanlder}</code><br>
	 * 因eric这里所做处理与框架中FileParser公共部分有少许不同,为了保证不修改框架,实现重写<br>
	 * 20170105
	 * @param field 
	 * 			要处理的字段
	 * @param str
	 * 			属性字符串
	 * @param map
	 * 			即将入库的一条记录,结构<"属性名","属性值">
	 */
	public void specialSplitHanlder(Field field, String str, Map<String, String> map) {
		if (StringUtil.isEmpty(str))
			return;
		String[] array = StringUtil.split(str, ",");
		for (int n = 1; n < array.length-1; n++) {
			map.put(field.getIndex() + "_" + array[n].trim(), array[++n].trim());
		}
		return;
	}
}
