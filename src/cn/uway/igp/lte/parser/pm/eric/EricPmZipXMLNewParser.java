package cn.uway.igp.lte.parser.pm.eric;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * lte eric性能解码器,xml格式,新文件格式,关键字段名全变化,20170407，dpf
 */
public class EricPmZipXMLNewParser extends EricPmZipXMLParser {

	public EricPmZipXMLNewParser() {
		super();
	}

	/**
	 * 模板传入接口
	 * 
	 * @param tmpfilename
	 */
	public EricPmZipXMLNewParser(String tmpfilename) {
		super(tmpfilename);
	}

	private String entryName = null;

	@Override
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
	protected void parseNextFile() throws XMLStreamException {
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
	protected void handle() {
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
	 * 1、ileHeader.dnPrefix：数据解析后是文件的全局数据<br>
	 * 2、measObjLdn：标记具体的网元对象<br>
	 * 3、measType：测量项<br>
	 * 4、r：测量项的值<br>
	 * 
	 * @param element
	 * @throws XMLStreamException
	 */
	protected void handleBeginElement(TagElement element) throws XMLStreamException {
		if (element.getName().equalsIgnoreCase("mt") || "measType".equalsIgnoreCase(element.getName())) {
			currentFields.add(StringUtil.nvl(reader.getElementText(), ""));
		} else if (element.getName().equalsIgnoreCase("r") && openFlag) {
			currentValues.add(StringUtil.nvl(reader.getElementText(), ""));
		} else if (element.getName().equalsIgnoreCase("moid") || "measValue".equalsIgnoreCase(element.getName())) {
			String moid;
			if (element.getName().equalsIgnoreCase("moid"))
				moid = StringUtil.nvl(reader.getElementText(), "");
			else
				moid = StringUtil.nvl(reader.getAttributeValue(null, "measObjLdn"), "");
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
		} else if (element.getName().equalsIgnoreCase("sn") || "fileHeader".equalsIgnoreCase(element.getName())) {
			List<String[]> list;
			if (element.getName().equalsIgnoreCase("sn"))
				list = parseSN(reader.getElementText());
			else
				list = parseSN(reader.getAttributeValue(null, "dnPrefix"));
			if (list == null || list.size() == 0) {
				// 修改 从entry 对象中取entryName 为直接使用entryName
				// 因为entry在<sn></sn>标签内容为空时它也为空
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
	 * 1、当碰到mv或者measType节点结束的时候，处理缓存的MT和R节点的值，添加至输出对象中<br>
	 * 2、1处理完成后，清空currentValues<br>
	 * 3、碰到mi或者measValue节点，清理currentFields
	 * 
	 * @param element
	 */
	protected void handleEndElement(TagElement element) {
		if ("mi".equalsIgnoreCase(element.getName()) || "measInfo".equalsIgnoreCase(element.getName())) {
			currentFields = new ArrayList<String>();
			return;
		}
		if (!element.getName().equalsIgnoreCase("mv") && !"measValue".equalsIgnoreCase(element.getName()))
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
	protected TagElement getNextValidTag() throws XMLStreamException {
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

	/* 解sn标签内容 */
	public static List<String[]> parseSN(String moid) {
		return parseKeyValue(moid, true);
	}

	/**
	 * 解析键值对字符串，即sn和moid标签中的内容，并存入有序列表。列表中的对象，是String数组， [0]为key,[1]为value. 参数str为要解析的字符串，isSN表示是否解析的是sn标签，sn标签中有同名的key，要特别处理。
	 */
	protected static List<String[]> parseKeyValue(String str, boolean isSN) {
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
}
