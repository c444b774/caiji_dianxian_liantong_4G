package cn.uway.igp.lte.parser.pm.zte;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.GZIPInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.lang.StringUtils;
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
 * lte中兴性能解码器(类XML)
 * 
 * @author guom
 */
public class ZteXmlParser extends FileParser {

	/** 日志记录器 */
	private static Logger LOGGER = LoggerFactory.getLogger(ZteXmlParser.class);

	/** 键值对拆分长度，如SBNID=1用=拆分之后的长度 */
	private static final int KEY_VALUE_LENTH = 2;

	private static final String myName = "中兴性能解析(类XML)";

	private XMLStreamReader reader;

	/** 结果集，存储一条解析记录 */
	private Map<String, String> resultMap = null;

	/** counter名,mt列表 */
	private String counterNames[] = null;

	/** counter值列表 */
	private String CounterValues[] = null;

	/** tar输入流 */
	private TarArchiveInputStream tarInputStream;

	private TarArchiveEntry entry = null;

	/**
	 * 模板传入接口
	 * 
	 * @param tmpfilename
	 */
	public ZteXmlParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void before() {
		super.before();
		try {
			// gz解压
			GZIPInputStream gzipstream = new GZIPInputStream(inputStream);
			// tar解压
			tarInputStream = new TarArchiveInputStream(gzipstream);
		} catch (IOException e) {
			throw new IllegalArgumentException("处理中兴性能文件失败", e);
		}
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();

		entry = tarInputStream.getNextTarEntry();
		if (entry == null) {
			return;
		}

		setCurrentDataTime(entry.getName());

		/** 对厂家文件进行过滤，因为厂家文件可能存在生成失败的情况而导致采集停滞 */
		if (!entry.getName().endsWith(".xml")) {
			LOGGER.warn(entry.getName() + "厂家文件错误，跳过该文件！");
			return;
		}
		XMLInputFactory fac = XMLInputFactory.newInstance();
		fac.setProperty("javax.xml.stream.supportDTD", false);
		reader = fac.createXMLStreamReader(tarInputStream);
	}
	
	@Override
	public boolean hasNextRecord() throws Exception {
		resultMap = new HashMap<String, String>();

		try {
			/** type记录stax解析器每次读到的对象类型，是element，还是attribute等等…… */
			int type = -1;

			/* 保存当前的xml标签名 */
			String tagName = null;

			String measValue = null;
			String measTypes = null;
			String measResults = null;
			String measTemp[] = null;

			try {
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
							/** 解析measTypes */
							if (tagName.equalsIgnoreCase("measTypes")) {
								/** 拆分measTypes标签，保存counter名 */
								measTypes = reader.getElementText();
								if (!"".equals(measTypes) || null != measTypes) {
									counterNames = measTypes.split(" ");
								} else {
									LOGGER.error(ZteXmlParser.class.toString() + ":厂家文件measTypes标签异常！");
									Exception e = new Exception("中兴性能文件解析过程出现厂家文件measTypes标签异常！" + counterNames.toString());
									throw e;
								}
							}
							/** 处理measValue标签，获得记录的SBNID、ENODEBID、CellID */
							else if (tagName.equalsIgnoreCase("measValue")) {
								measValue = reader.getAttributeValue(0);
								String measValues[] = measValue.split(",");
								if (null == measValues || measValues.length == 0) {
									LOGGER.error(ZteXmlParser.class.toString() + ":厂家文件measValue标签异常！");
									Exception e = new Exception("中兴性能文件解析过程出现厂家文件measValue标签异常！" + measValues);
									throw e;
								}
								for (String str : measValues) {
									measTemp = str.split("=");
									if (null != measTemp && measTemp.length == KEY_VALUE_LENTH) {
										resultMap.put(measTemp[0].toUpperCase(), measTemp[1]);
									} else {
										LOGGER.error(ZteXmlParser.class.toString() + ":厂家文件measObjLdn标签异常！");
										Exception e = new Exception("中兴性能文件解析过程出现厂家文件measObjLdn标签异常！" + measTemp.toString());
										throw e;
									}

								}
							}
							/** 解析measResults并拆分获得counter值 */
							else if (tagName.equalsIgnoreCase("measResults")) {
								measResults = reader.getElementText();
								if (!"".equals(measResults) || null != measResults) {
									CounterValues = measResults.split(" ");
								} else {
									LOGGER.error(ZteXmlParser.class.toString() + ":厂家文件measResults标签异常！");
									Exception e = new Exception("中兴性能文件解析过程出现厂家文件measResults标签异常！" + measResults);
									throw e;
								}

							}
							break;
						case XMLStreamConstants.END_ELEMENT :
							/** measValue结束标签结束一条记录解析，对解析数据进行组装 */
							if (tagName.equalsIgnoreCase("measValue")) {

								/** 初始化解析文件对应的模板 */
								String fileName = entry.getName();
								templet = findTemplet(fileName);
								if (templet == null) {
									RuntimeException e = new RuntimeException(task.getId() + ", fileName= " + fileName + ",对应的解析模板为空！");
									throw e;
								}

								/** counter数量与其值的数量保持一致 */
								if (counterNames.length == CounterValues.length) {
									for (int i = 0; i < counterNames.length; i++) {
										resultMap.put(counterNames[i].toUpperCase(), CounterValues[i]);
									}
								}
								// sunt 2015-05-14 发现中兴的lte性能数据文件有measResults未空节点的情况。
								// 处理方式：指标值置null
								else if((counterNames.length>0)&&StringUtils.isBlank(measResults)){
									for (int i = 0; i < counterNames.length; i++) {
										resultMap.put(counterNames[i].toUpperCase(), null);
									}
								}
								// 之前直接抛异常，太简单粗暴了。这里只打印错误日志，保证正常记录正常入库。
								else {
									LOGGER.error(ZteXmlParser.class.toString() + "中兴性能文件解析过程出现厂家文件measResults标签异常，measResults与measTypes数量不一致！"
											+ "fileName"+fileName+";counterNames.len="
											+counterNames.length+";measResults:"+measResults);
									continue;
								}
								return true;
							}
							break;
						default :
							break;
					}
				}
			} catch(RuntimeException e){
				LOGGER.warn(e.getMessage());
			} catch (Exception e) {
				LOGGER.error("解析文件：{} 出现问题，{}", new Object[]{entry.getName(), e});
			}
			
			while ((entry = tarInputStream.getNextTarEntry()) != null) {
				setCurrentDataTime(entry.getName());

				/** 对厂家文件进行过滤，因为厂家文件可能存在生成失败的情况而导致采集停滞 */
				if (!entry.getName().endsWith(".xml")) {
					LOGGER.warn(entry.getName() + "厂家文件错误，跳过该文件！");
					continue;
				}
				XMLInputFactory fac = XMLInputFactory.newInstance();
				fac.setProperty("javax.xml.stream.supportDTD", false);
				reader = fac.createXMLStreamReader(tarInputStream);
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
	 * 功能描述：从模板中查询对应被解析文件的模板
	 * 
	 * @author guom
	 * @param 解析文件名
	 * */
	private Templet findTemplet(String fileName) {
		if ("".equals(fileName) || null == fileName) {
			return null;
		}
		//PM201507111630+0800_20150711.1600+0800-1615+0800_V12.14.31_CELLBLER.xml
		//PM201504231015+0800_20150423.0945+0800-1000+0800_CELLBLER_R1.xml
		//PM201504231015+0800_20150423.0945+0800-1000+0800_CELLBLER.xml
		Templet templet = null;
//		String regex = "_%s[._]";
		for (Entry<String, Templet> entry : templetMap.entrySet()) {
//			Pattern pattern = Pattern.compile(String.format(regex, entry.getKey()));
//			Matcher matcher = pattern.matcher(fileName);
//			if(matcher.find()){
//				templet = entry.getValue();
//				break;
//			}
			
			String fileTag = "_" + entry.getKey();
			int nPos = fileName.indexOf(fileTag);
			if (nPos > 0) {
				int nNextCharPos = nPos + fileTag.length();
				String nextChar = fileName.substring(nNextCharPos, nNextCharPos + 1);
				if (nextChar.equals("_") || nextChar.equals(".")) {
					templet = entry.getValue();
					break;
				}
			}
		}
		return templet;
	}

	/**
	 * 解析文件名
	 * 
	 * @throws Exception
	 */
	public void parseFileName() {
		try {
			String fileName = FileUtil.getFileName(this.rawName);
			String patternTime = StringUtil.getPattern(fileName, "\\d{8}[_]\\d{4}");
			if (patternTime != null) {
				this.currentDataTime = TimeUtil.getyyyyMMdd_HHmmDate(patternTime);
			}
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
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
			if (tarInputStream != null) {
				tarInputStream.close();
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