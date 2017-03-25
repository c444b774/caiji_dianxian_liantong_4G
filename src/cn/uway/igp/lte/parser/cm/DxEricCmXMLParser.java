package cn.uway.igp.lte.parser.cm;

import java.text.DecimalFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.util.TimeUtil;

public class DxEricCmXMLParser extends EricCmXMLParser{
	
	private static Logger LOGGER = LoggerFactory.getLogger(DxEricCmXMLParser.class);
	
	public DecimalFormat d6f = new DecimalFormat("0.000000");
	
	public DxEricCmXMLParser() {
	}
	
	public DxEricCmXMLParser(String tmpfilename) {
		super(tmpfilename);
	}
	
	@Override
	public ParseOutRecord nextRecord() throws Exception {
		readLineNum++;
		ParseOutRecord record = new ParseOutRecord();
		List<Field> fieldList = templet.getFieldList();
		// Map<String, String> map = new HashMap<String, String>();
		Map<String, String> map = this.createExportPropertyMap(templet.getDataType());
		for (Field field : fieldList) {
			if (field == null) {
				continue;
			}
			//String value = resultMap.get(field.getName().trim().toUpperCase());
			String value = resultMap.get(field.getIndex());
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
			// 将厂家中经纬度由度分秒转换为度，保留6位小数
			value = convertValue(value,field.getIndex());
			map.put(field.getIndex(), value);
		}

		// 公共回填字段
		map.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		handleTime(map);

		// 网元信息
		putOtherInfo(map);

		record.setType(templet.getDataType());
		record.setRecord(map);
//		此处注释掉，还有些记录些要复用这个resultMap;
//		if (!personlyFlag) {
//			resultMap.clear();
//			resultMap = null;
//		}
		if(personlyFlag){
			tmpMap = map;
		}
		
		// 第一个ueMeasIntraFreq1与第二个ueMeasIntraFreq2相同时只入库一条记录，否则出现唯一性约束
		if(isFreq2){
			if((tmpMap.get("REPORTCONFIGEUTRAINTRAFREQPMREF") == null && map.get("REPORTCONFIGEUTRAINTRAFREQPMREF") == null)||(tmpMap.get("REPORTCONFIGEUTRAINTRAFREQPMREF") != null 
					&& tmpMap.get("REPORTCONFIGEUTRAINTRAFREQPMREF").equals(map.get("REPORTCONFIGEUTRAINTRAFREQPMREF")))){
				
				if((tmpMap.get("EUTRANFREQUENCY") == null && map.get("EUTRANFREQUENCY") == null)
						|| (tmpMap.get("EUTRANFREQUENCY") != null 
						&&  tmpMap.get("EUTRANFREQUENCY").equals(map.get("EUTRANFREQUENCY")))){
					isFreq2 = false;
					tmpMap = null;
					return null;
				}else{
					isFreq2 = false;
					tmpMap = null;
				}
			
			}else{
				isFreq2 = false;
				tmpMap = null;
			}
		} 		
		
		return record;
	}
	
	private String convertValue(String value,String index)
	{
		double degree = 0;
		double mintue = 0;
		double second = 0;
		if("LONGITUDE".equals(index.toUpperCase())
				&& value.trim().length() > 5){
			degree = Double.valueOf(value.substring(0, 3));
			mintue = Double.valueOf(value.substring(3, 5));
			if(value.endsWith("00")){
				second = Double.valueOf(value.substring(5))/100D;
			}else{
				second = Double.valueOf(value.substring(5));
			}
			
		}else if("LATITUDE".equals(index.toUpperCase())
				&& value.trim().length() > 4){
			degree = Double.valueOf(value.substring(0, 2));
			mintue = Double.valueOf(value.substring(2, 4));
			if(value.endsWith("00")){
				second = Double.valueOf(value.substring(4))/100D;
			}else{
				second = Double.valueOf(value.substring(4));
			}
		}
		if(degree != 0 
				|| mintue != 0
				|| second != 0)
		{
			value = d6f.format(degree + mintue/60D + second/3600D);
		}
		return value;
	}

}
