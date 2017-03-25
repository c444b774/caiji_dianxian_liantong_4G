package cn.uway.igp.lte.parser.cfc.zte;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.file.CSVParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;

/**
 * lte中兴配置(参数)解码器(广东)
 * 
 * @author yuy
 * @date 2014.1.7
 */
public class ZteCfcCSVParser extends CSVParser {

	private static Logger LOGGER = LoggerFactory.getLogger(ZteCfcCSVParser.class);

	/**
	 * 输入zip流
	 */
	public ZipInputStream zipstream;

	/**
	 * 文件名
	 */
	public String entryName;

	/**
	 * templet对象迭代器
	 */
	public Iterator<String> templetMapIt;

	public ZteCfcCSVParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void before() {
		super.before();

		// zip压缩包
		zipstream = new ZipInputStream(inputStream);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;

		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		try {
			// 如果当前ZipEntry仍然可读 或者还有未读取的ZipEntry 返回true 否则false
			 if (reader != null && (currentLine = reader.readLine()) != null) {
			 return true;
			 }

			ZipEntry entry = null;
			while ((entry = zipstream.getNextEntry()) != null) {
				entryName = entry.getName();
				templetMapIt = templetMap.keySet().iterator();
				while (templetMapIt.hasNext()) {
					String file = templetMapIt.next();
					// 匹配要解析的文件
					if (wildCardMatch(file, entryName)) {
						templet = templetMap.get(file);
						// 重新创建一个BufferedReader,原来的BufferedReader等待垃圾回收。不能直接关闭，否则会关闭封装的ZipStream
						reader = new BufferedReader(new InputStreamReader(zipstream, "GBK"), 16 * 1024);
						// 读文件头
						readHead();

						currentLine = reader.readLine();
						if (currentLine == null) {
							break;
						}
						// 字段定位
						setFieldLocalMap(head);
						return true;
					}
				}
			}
			return false;
		} catch (IOException e) {
			this.cause = "【中兴配置CSV解码】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
	}

	/**
	 * 处理头部
	 * 
	 * @throws Exception
	 */
	public void readHead() throws Exception {
		head = reader.readLine();
		if (head == null) {
			throw new NullPointerException("head is null，解码退出");
		}
		LOGGER.debug("[{}]-获取头line={}", task.getId(), head);
	}

	/**
	 * 解析文件名
	 * 
	 * @throws ParseException
	 */
	public void parseFileName() {
		try {
			String fileName = FileUtil.getFileName(this.rawName);

			String patternTime = StringUtil.getPattern(fileName, "[_]\\d{8}");
			if (patternTime != null) {
				patternTime = patternTime.replace("_", "");
				this.currentDataTime = getDateTime(patternTime, "yyyyMMdd");
			}
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

	/**
	 * 通配符匹配
	 * 
	 * @param src
	 *            解析模板中的文件名或classname
	 * @param dest
	 *            厂家原始文件，如果是压缩包，则会带路径
	 * @param wildCard
	 *            通配符
	 * @return
	 */
	public static boolean wildCardMatch(String src, String dest) {
		String tmp = src.replace("*", "(.)*").toUpperCase();
		// 20160126 解析模板中没有文件后缀时，截取出文件名称来做比较
		String tempDest = dest.substring(0, dest.lastIndexOf(".")).toUpperCase();
		if (dest.contains("/")) {
			int lastIndex = dest.lastIndexOf("/");
			String tmpFileNameOrClassName = dest.substring(lastIndex + 1).toUpperCase();
			tempDest = tempDest.substring(lastIndex + 1);
			// 20160126 解析模板匹配规则修改为带后缀匹配与不带后缀匹配两种模式，匹配一种即可
			if (tmp.equals(tmpFileNameOrClassName) || tmp.equals(tempDest)) {
				return true;
			}
		} else {
			// 20160126 解析模板匹配规则修改为带后缀匹配与不带后缀匹配两种模式，匹配一种即可
			if (tmp.equals(dest.toUpperCase()) || tmp.equals(tempDest)) {
				return true;
			}
			// 100001_LTEFDD_SUBNETWORK_201602200500 EXTERNALUTRANTCELLTDD
		}
		return false;
	}

	public static boolean wildCardMatch2(String src, String dest) {
		String reg = src.replace("*", "(.)*").toUpperCase();
		if (dest.contains("/")) {
			int lastIndex = dest.lastIndexOf("/");
			String tmpFileNameOrClassName = dest.substring(lastIndex + 1).toUpperCase();
			return regexMatch(reg, tmpFileNameOrClassName);
		}
		return regexMatch(reg, dest);
	}

	private static boolean regexMatch(String src, String dest) {
		String[] regex = src.split("\\|");
		boolean result = false;

		for (String reg : regex) {
			Pattern pattern = Pattern.compile(reg);
			Matcher matcher = pattern.matcher(dest);
			result = matcher.find();
			if (result)
				break;
		}
		return result;
	}

}
