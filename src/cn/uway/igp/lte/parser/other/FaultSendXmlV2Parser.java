package cn.uway.igp.lte.parser.other;

import java.io.IOException;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import cn.uway.framework.parser.file.templet.HttpTemplet;
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.igp.lte.parser.cm.ZteXmlParser;
import cn.uway.igp.lte.templet.xml.HttpXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class FaultSendXmlV2Parser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(FaultSendXmlV2Parser.class);

	private static final String myName = "新客保解析（ftp接口）";

	/** xml流 */
	public XMLStreamReader reader = null;

	private Map<String, String> resultMap = null;

	private String fileTag = null;
	

	public FaultSendXmlV2Parser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.before();// accessOutObject.getRawAccessName()

		LOGGER.debug(" 开始解码:{}", "");
		// 解析模板 获取当前文件对应的templet
		parseTemplet();
		// 将原来的流加上xml根标识
		String encode ="GBK";

		reader = XMLInputFactory.newInstance().createXMLStreamReader(inputStream, encode);
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		resultMap = new HashMap<String, String>();
		try {
			/** type记录stax解析器每次读到的对象类型，是element，还是attribute等等…… */
			int type = -1;

			/* 保存当前的xml标签名 */
			String tagName = null;
			/* 标签对应的值 */
			String fieldValue = null;

			int tagCounter = 0;

			/** 初始化解析文件对应的模板,模板名称为不带参数的url,模板配置中需加上http:// */
			templet = findTemplet(accessOutObject.getRawAccessName());
			if (templet != null) {
				// 初始化warehouse输出启动输出，解决没有数据时，无log_clt_insert日志情况
				this.createExportPropertyMap(templet.dataType);
				/* 将xml的第一个碰到的名称作为tagName */
				HttpTemplet httpTemplet = (HttpTemplet) templet;
				fileTag = httpTemplet.getDataName();
				/* 开始迭代读取xml文件 */
				while (reader.hasNext()) {
					type = reader.next();
					if (type == XMLStreamConstants.START_ELEMENT || type == XMLStreamConstants.END_ELEMENT) {
						tagName = reader.getLocalName();
					}
					if (tagName == null) {
						continue;
					}
					switch (type) {
						case XMLStreamConstants.START_ELEMENT :
							if (isExistInTemplet(templet, tagName)) {
								if (tagName.equals(fileTag)) {
									try {
										fieldValue = reader.getAttributeValue(0);
									} catch (Exception e) {
										LOGGER.warn("" + " 文件标签解析有误，请查看厂家返回是否有误");
										// requestURLIncludeTimenoParams
									}
								} else {
									fieldValue = reader.getElementText();
								}
								// 值为null的，转为空保存
								fieldValue = fieldValue == null ? "" : fieldValue;
								fieldValue = "null".equalsIgnoreCase(fieldValue) ? "" : fieldValue;
								resultMap.put(tagName.toUpperCase(), fieldValue);
							}
							break;
						case XMLStreamConstants.END_ELEMENT :
							/** 当每条记录的根标签结束时候返回处理 */
							if (tagName.equalsIgnoreCase(fileTag)) {
								return true;
							}
							break;
						default :
							break;
					}
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
				invalideNum++;
				return null;
			}

			if ("true".equals(field.getIsPassMS())) {
				int i = value.indexOf(".");
				value = (i == -1 ? value : value.substring(0, i));
			}
			map.put(field.getIndex(), null != value ? value.trim() : value);
		}

		/** 对于所有采集字段为空的记录，放弃入库。因为该记录入库会导致主键冲突 */
		int fieldCount = map.keySet().size();
		int nullCount = 0;
		for (String filed : map.keySet()) {
			if (map.get(filed) == null || "".equals(map.get(filed))) {
				nullCount++;
			}
		}
		if (nullCount == fieldCount) {
			return null;
		}

		// 公共回填字段
		map.put("OMCID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		handleTime(map);
		record.setType(templet.getDataType());
		record.setRecord(map);
		return record;
	}

	@Override
	public void close() {
		/** 关闭解析类文件流对象 */
		try {
			if (reader != null) {
				reader.close();
			}
			if (inputStream != null) {
				inputStream.close();
			}
		} catch (XMLStreamException e) {
			LOGGER.warn(ZteXmlParser.class + "类关闭文件流异常！" + e.getMessage());
		} catch (IOException e) {
			LOGGER.warn(ZteXmlParser.class + "类关闭文件流异常！" + e.getMessage());
		}

		// 标记解析结束时间
		this.endTime = new Date();

		LOGGER.debug("[{}]-{}，处理{}条记录", new Object[]{task.getId(), myName, readLineNum});
	}

	public void parseTemplet() throws Exception {
		HttpXmlTempletParser templetParser = new HttpXmlTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();
		templetMap = templetParser.getTemplets();
	}

	/**
	 * 功能描述：从模板中查询对应被解析文件的模板 这里对应与模板中的url
	 * 
	 * @author guom
	 * @param 解析文件名
	 * */
	private Templet findTemplet(String fileName) {
		String tmpFileName = "";
		Templet templetTemp = null;
		if ("".equals(fileName) || null == fileName) {
			return null;
		}
		Set<String> mapSet = templetMap.keySet();
		tmpFileName = fileName;
		for (String str : mapSet) {
				templetTemp = templetMap.get("WXSERVICE");
				return templetTemp;
		}
		return null;
	}

	/**
	 * 查询厂家文件中的tag是否在对应的模板中存在
	 * 
	 * @param templet
	 *            厂家文件对应的模板
	 * @param tagName
	 *            厂家文件中的节点
	 * */
	private boolean isExistInTemplet(Templet templet, String tagName) {

		List<Field> fieldList = templet.getFieldList();
		for (Field field : fieldList) {
			if (field.getName().equalsIgnoreCase(tagName)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * 解析文件名
	 * 
	 * @throws ParseException
	 */
	public void parseFileName() {
		try {
			String fileName = FileUtil.getFileName(this.rawName);
				this.currentDataTime = TimeUtil.getyyyyMMddHHmmssDate(fileName);
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

}
