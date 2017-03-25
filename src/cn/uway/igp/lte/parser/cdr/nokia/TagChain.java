package cn.uway.igp.lte.parser.cdr.nokia;

public class TagChain {
	private String[] tags;
	private Integer maxSize=5;
	private static Integer STEP = 5;
	private Integer size;
	
	public TagChain(){
		tags = new String[maxSize];
		size = 0;
	}
	
	public void add(String tagName){
		if(size+1 == maxSize){
			String[] tmp = new String[maxSize+STEP];
			System.arraycopy(tags, 0, tmp, 0, maxSize);
			maxSize = tmp.length;
			tags = tmp;
		}
		tags[size++] = tagName;
	}
	
	public void remove(String tagName){
		if(isEmpty()){
			return;
		}
		if(tags[size-1].equals(tagName)){
			tags[--size] = null;
		}
	}
	
	public void clear(){
		if(!isEmpty()){
			tags = new String[maxSize];
			size = 0;
		}
	}
	
	private Boolean isEmpty(){
		return size == 0;
	}
	
	public String getChainStr(){
		if(isEmpty()){
			return "";
		}
		StringBuffer sb = new StringBuffer();
		for(int i=0; i<size; i++){
			sb.append(tags[i]).append(" ");
		}
		return sb.toString();
	}
	
}
