package cn.uway.igp.lte.parser.pm.eric;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import cn.uway.igp.lte.templet.xml.EricPmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * lte eric性能解码器(xml格式)
 * 
 * @author yuy @ 24 May, 2014
 */
public class EricPmXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(EricPmXMLParser.class);

	public static String myName = "爱立信性能XML解析";

	public XMLStreamReader reader;

	private String subnetworkRoot;

	private String subnetwork;

	private String meContext;

	/** 结果map */
	public Map<String, String> resultMap = null;

	/** counter名,mt列表 */
	public List<String> currMT;

	/** 读取开关 */
	public boolean on_off = false;

	public EricPmXMLParser() {

	}

	/**
	 * 模板传入接口
	 * 
	 * @param tmpfilename
	 */
	public EricPmXMLParser(String tmpfilename) {
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
		fac.setProperty("javax.xml.stream.supportDTD", false);
		fac.setProperty("javax.xml.stream.isValidating", false);

		reader = fac.createXMLStreamReader(inputStream);
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
			// moid中最后一个属性名
			String name = null;
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
							currR.add(StringUtil.nvl(reader.getElementText(), ""));
						} else if (tagName.equalsIgnoreCase("mt")) {
							if (currMT == null)
								currMT = new ArrayList<String>();
							/** 处理mt标签，读取counter名 */
							currMT.add(StringUtil.nvl(reader.getElementText(), ""));
						} else if (tagName.equalsIgnoreCase("moid")) {
							moid = StringUtil.nvl(reader.getElementText(), "");
							String[] array = StringUtil.split(moid, ",");
							if (array != null && array.length > 0) {
								String tmp = array[array.length - 1];
								name = tmp.substring(0, tmp.indexOf("="));
								if (findMyTemplet(name)) {
									/** 读取开关开启 */
									on_off = true;
								}
								break;
							}
						} else if (tagName.equalsIgnoreCase("sn")) {
							/** 处理sn标签，读取rnc名、nodeb名等信息。 */
							List<String[]> list = parseSN(reader.getElementText());
							if (list == null || list.size() == 0) {
								String fileName = FileUtil.getFileName(this.rawName);
								list = parseSN(fileName.substring(fileName.indexOf("_SubNetwork") + 1, fileName.lastIndexOf("_statsfile")));
							}
							this.subnetworkRoot = findByName(list, "SubNetworkRoot");
							this.subnetwork = findByName(list, "SubNetwork");
							this.meContext = findByName(list, "MeContext");
						}
						break;
					case XMLStreamConstants.END_ELEMENT :
						/** 遇到mv结束标签，应处理并清空r列表和当前moid */
						if (tagName.equalsIgnoreCase("mv")) {
							if (currMT != null && currR != null && on_off) {
								for (int n = 0; n < currMT.size() && n < currR.size(); n++) {
									resultMap.put(currMT.get(n).toUpperCase(), currR.get(n));
								}
								resultMap.put("SUBNETWORK_ROOT", subnetworkRoot);
								resultMap.put("SUBNETWORK", subnetwork);
								resultMap.put("MECONTEXT", meContext);
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
						break;
					default :
						break;
				}
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
		setCurrentDataTime(this.rawName);
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

	/* 在List<String[]>中按key名查找value. */
	public static String findByName(List<String[]> list, String name) {
		if (list == null || name == null)
			return "";
		for (String[] arr : list) {
			if (arr[0].equalsIgnoreCase(name))
				return arr[1];
		}
		return "";
	}
}
