package cn.uway.igp.lte.parser.unicome.alu;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;

import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.igp.lte.parser.pm.alu.LucPmXMLParser;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class WLucPmXMLParser extends LucPmXMLParser {
	
	private String rncName;
	private String subnetwork;
	private String managedelement;
	
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
				} catch (Exception e) {
					continue;
				}
				if (type == XMLStreamConstants.START_ELEMENT || type == XMLStreamConstants.END_ELEMENT)
					tagName = reader.getLocalName();
				if (tagName == null) {
					continue;
				}
				switch (type) {
					case XMLStreamConstants.START_ELEMENT :
						if (tagName.equalsIgnoreCase("r") && on_off) {
							/** 处理r标签，读取counter值 */
							String rVal = reader.getElementText();
							if (rVal != null) {
								if (rVal.trim().equalsIgnoreCase("null"))
									rVal = "";
							}
							currR.add(rVal.toUpperCase());
						} else if (tagName.equalsIgnoreCase("mt")) {
							if (currMT == null)
								currMT = new ArrayList<String>();
							/** 处理mt标签，读取counter名 */
							currMT.add(StringUtil.nvl(reader.getElementText(), "").toUpperCase());
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
						} else if (tagName.equals("sn")) {
							/* 处理sn标签，读取rnc名、nodeb名等信息。 */
							String[] list = parseSN(reader.getElementText());
							this.rncName = list[0];
							this.subnetwork = list[1];
							this.managedelement = list[2];
						}
						break;
					case XMLStreamConstants.END_ELEMENT :
						/** 遇到mv结束标签，应处理并清空r列表和当前moid */
						if (tagName.equalsIgnoreCase("mv")) {
							if (currMT != null && currR != null && on_off) {
								for (int n = 0; n < currMT.size() && n < currR.size(); n++) {
									resultMap.put(currMT.get(n).toUpperCase(), currR.get(n));
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
		map.put("OMCID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		map.put("STAMPTIME", TimeUtil.getDateString(this.currentDataTime));
		map.put("RNC_NAME", this.rncName);
		map.put("SUBNETWORK", this.subnetwork);
		map.put("MANAGEDELEMENT", this.managedelement);
		handleTime(map);
		record.setType(templet.getDataType());
		record.setRecord(map);
		return record;
	}
	
	/**
	 * 解析sn节点中的内容，返回结果为String数据，长度为3，第0个内容为rnc_name，第1个内容为subnetwork， 第2个内容为managedelement.
	 */
	public static String[] parseSN(String sn) {
		String[] ret = new String[]{"", "", ""};
		String[] sp0 = sn.split(",");
		for (String s0 : sp0) {
			String[] sp1 = s0.split("=");
			if (sp1[0].equals("subNetwork") || sp1[0].equals("SubNetwork")) {
				/* sn中有两个subNetwork，第一个是rnc_name，第二个是subnetwork */
				if (ret[0].isEmpty())
					ret[0] = sp1[1];
				else
					ret[1] = sp1[1];
			} else if (sp1[0].equals("ManagedElement")) {
				ret[2] = sp1[1];
			}
		}
		return ret;
	}


}
