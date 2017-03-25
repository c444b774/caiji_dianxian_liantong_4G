package cn.uway.igp.lte.templet.xml;

import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Node;

import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.StringUtil;

public class PtPmCmXmlTempletParser extends TempletParser {

	/** <indexs(对应indexs属性)> */
	public Map<String, String[]> indexsMap = new HashMap<String, String[]>();

	/**
	 * 处理file属性
	 * 
	 * @param templet
	 * @param templetNode
	 * @throws Exception
	 */
	public void personalHandler(Templet templet, Node templetNode) throws Exception {
		Node measurementTypeNode = templetNode.getAttributes().getNamedItem("ObjectType");
		if (measurementTypeNode != null) {
			String measurementTypeName = measurementTypeNode.getNodeValue();
			if (StringUtil.isEmpty(measurementTypeName))
				throw new Exception("ObjectType属性值不能为空");
			templet.setDataName(measurementTypeName.trim());
			
		} else
			throw new Exception("缺少ObjectType属性");
	}
}
