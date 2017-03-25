package cn.uway.igp.lte.parser.pm.alu;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
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
public class LucPmTarXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(LucPmXMLParser.class);

	public static String myName = "朗讯性能解析(类XML)";
	
	// 解析的文件
	protected String rawFilePath = null;

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

	public boolean isZip = true;
	public boolean isTar = true;

	/** 输入zip流 */
	public TarArchiveInputStream tarstream;

	public TarArchiveEntry entryTar = null;

	public LucPmTarXMLParser() {

	}

	/**
	 * 模板传入接口
	 * 
	 * @param tmpfilename
	 */
	public LucPmTarXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		if (accessOutObject == null)
			throw new IOException("接入对象无效，null.");
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();
		this.rawFilePath = this.accessOutObject.getRawAccessName();
		
		setCurrentDataTime(rawFilePath);
		// 转换为缓冲流读取
		if (this.rawName.endsWith(".zip")) {
			ZipInputStream zipInput = new ZipInputStream(inputStream);
			if (zipInput.getNextEntry() != null)
				this.reader = XMLInputFactory.newInstance().createXMLStreamReader(new InputStreamReader(zipInput, "GBK"));
			else {
				LOGGER.warn("压缩包中无文件，文件解析异常。文件：{}", this.rawFilePath);
				return;
			}
		} else if (accessOutObject.getRawAccessName().endsWith(".gz")) {
			this.reader = XMLInputFactory.newInstance().createXMLStreamReader(new InputStreamReader(new GZIPInputStream(inputStream), "UTF-8"));
		} else {
			this.reader = XMLInputFactory.newInstance().createXMLStreamReader(new InputStreamReader(inputStream, "UTF-8"));
		}
				
				
//	

	}

	@Override
	public boolean hasNextRecord() throws Exception {
		if (reader == null)
			return false;
		resultMap = new HashMap<String, String>();
		try {
			String entryName = rawName;
			/* type记录stax解析器每次读到的对象类型，是element，还是attribute等等…… */
			int type = -1;
			/* 保存当前的xml标签名 */
			String tagName = null;
			/* 当前r列表 */
			List<String> currR = new ArrayList<String>();
			// moid 数据唯一标识
			String moid = null;
			
			try {
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
								currR.add(StringUtil.nvl(reader.getElementText(), "").toUpperCase());
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
							} else if (tagName.equals("mdc")) {

							}
							break;
						default :
							break;
					}
				}
			} catch (Exception e1) {
				LOGGER.error("解析文件：{} 出现问题，{}", new Object[]{entryName, e1});
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
			if (tarstream != null) {
				tarstream.close();
			}
		} catch (XMLStreamException e) {
			LOGGER.warn("阿朗性能文件解析完成之后关闭文件流异常！");
		} catch (IOException e) {
			LOGGER.warn("阿朗性能文件解析完成之后关闭文件流异常！");
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
		TempletParser templetParser = new LucPmXmlTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();

		templetMap = templetParser.getTemplets();
	}

	/**
	 * 跳过错误文件的日志输出
	 */
	public void jumpFilelogOutput(IOException e) {
//		String entryname = entryTar != null ? entryTar.getName() :"";
		String loginfo = "IO读取压缩包文件" + rawName + "时，gz文件" + entryTar.getName() + "存在问题,跳过此文件,解码继续."+e.getMessage();
		LOGGER.error(loginfo);
		badWriter.error(loginfo);
	}
	
	// 主动设置currentDataTime(只适用于带时区的文件名，如爱立信、阿朗)
		public void setCurrentDataTime(String fileName) {
			try {
				int lastIndex = FileUtil.getLastSeparatorIndex(fileName);
				fileName =  fileName.substring(lastIndex + 1);
//				fileName = FileUtil.getFileName(fileName);
				String patternTime = StringUtil.getPattern(fileName, "\\d{8}[.]\\d{4}[+]\\d{2}");
				if (patternTime != null) {
					int addPlace = patternTime.indexOf("+");
					String currPatternTime = patternTime.substring(0, addPlace).replace(".", "_");
					// 不考虑时区
					Date dataTime = TimeUtil.getyyyyMMdd_HHmmDate(currPatternTime);
					// 考虑时区
					int timeZone = Integer.parseInt(patternTime.substring(addPlace + 1));
					int beijingTimeZone = 8;// 北京时区 东八区
					if (timeZone == beijingTimeZone)
						this.currentDataTime = dataTime;
					else {
						Calendar calendar = Calendar.getInstance();
						calendar.setTime(dataTime);
						calendar.add(Calendar.HOUR, beijingTimeZone - timeZone);
						this.currentDataTime = calendar.getTime();
					}
				}
			} catch (Exception e) {
				LOGGER.debug("解析文件名异常", e);
			}
		}
	


}