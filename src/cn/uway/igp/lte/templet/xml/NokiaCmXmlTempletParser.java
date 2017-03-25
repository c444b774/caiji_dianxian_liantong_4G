package cn.uway.igp.lte.templet.xml;

import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Node;

import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.StringUtil;

public class NokiaCmXmlTempletParser extends TempletParser {

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
		Node classNameNode = templetNode.getAttributes().getNamedItem("className");
		if (classNameNode != null) {
			String className = classNameNode.getNodeValue();
			if (StringUtil.isEmpty(className))
				throw new Exception("className属性值不能为空");
			templet.setDataName(className.trim());
		} else
			throw new Exception("缺少className属性");
	}
}
