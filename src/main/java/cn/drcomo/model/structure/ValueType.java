package cn.drcomo.model.structure;

public enum ValueType {
    TEXT,
    INTEGER,
    DOUBLE;

    public static boolean isValid(ValueType type,String currentValue){
        if(type.equals(ValueType.INTEGER)){
            try{
                Long.parseLong(currentValue);
            }catch(Exception e){
                return false;
            }
        }else if(type.equals(ValueType.DOUBLE)){
            try{
                Double.parseDouble(currentValue);
            }catch(Exception e){
                return false;
            }
        }
        return true;
    }
}
