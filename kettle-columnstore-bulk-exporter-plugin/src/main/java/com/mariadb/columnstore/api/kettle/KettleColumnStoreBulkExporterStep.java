/*
 * Copyright (c) 2018 MariaDB Corporation Ab
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE file and at www.mariadb.com/bsl11.
 *
 * Change Date: 2021-04-01
 *
 * On the date above, in accordance with the Business Source License, use
 * of this software will be governed by version 2 or later of the General
 * Public License.
 */

package com.mariadb.columnstore.api.kettle;

import com.mariadb.columnstore.api.*;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.row.value.ValueMetaTimestamp;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;

import static org.pentaho.di.core.row.ValueMetaInterface.*;

/**
 *   
 * This class is the implementation of StepInterface.
 * The class is responsible for:
 * 
 * - initializing the step
 * - executing the row processing logic
 * - disposing of the step
 *
 * Any information related to the processing logic
 * is stored in the supplied step data interface.
 * 
 */

public class KettleColumnStoreBulkExporterStep extends BaseStep implements StepInterface {

  private static final Class<?> PKG = KettleColumnStoreBulkExporterStepMeta.class; // for i18n purposes

  private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
  private ValueMetaTimestamp valueMetaTimestamp = new ValueMetaTimestamp();

  /**
   * The constructor should simply pass on its arguments to the parent class.
   * 
   * @param s                 step description
   * @param stepDataInterface step data class
   * @param c                 step copy
   * @param t                 transformation description
   * @param dis               transformation executing
   */
  public KettleColumnStoreBulkExporterStep( StepMeta s, StepDataInterface stepDataInterface, int c, TransMeta t, Trans dis ) {
    super( s, stepDataInterface, c, t, dis );
  }

  /**
   * This method is called by PDI during transformation startup. 
   * 
   * It's initialization is required for step execution. 
   * 
   * The meta and data implementations passed in are safely cast
   * to the step's respective implementations. 
   * 
   * It is mandatory that super.init() is called to ensure correct behaviour.
   * 
   * @param smi  step meta interface implementation, containing the step settings
   * @param sdi  step data interface implementation, used to store runtime information
   * 
   * @return true if initialization completed successfully, false if there was an error preventing the step from working. 
   *  
   */
  public boolean init( StepMetaInterface smi, StepDataInterface sdi ){
    // Casting to step-specific implementation classes is safe
    KettleColumnStoreBulkExporterStepMeta meta = (KettleColumnStoreBulkExporterStepMeta) smi;
    KettleColumnStoreBulkExporterStepData data = (KettleColumnStoreBulkExporterStepData) sdi;
    if ( !super.init( meta, data ) ) {
      return false;
    }

    // Initialize the ColumnStore Driver
    data.d = meta.initializeColumnStoreDriver(getTransMeta());
    if(data.d == null){
        logError("The ColumnStoreDriver couldn't be instantiated.");
        setErrors(1);
        return false;
    }

    logBasic("mcsapi version: " + data.d.getVersion());
    logBasic("javamcsapi version: " + data.d.getJavaMcsapiVersion());
    if(log.isRowLevel()){
        data.d.setDebug((short)2);
    }
    data.catalog = data.d.getSystemCatalog();
    try {
        data.table = data.catalog.getTable(meta.getTargetDatabase(), meta.getTargetTable());
    }catch(ColumnStoreException e){
        if(log.isRowLevel()){
            data.d.setDebug((short)0);
        }
        logError("Target table " + meta.getTargetTable() + " doesn't exist.", e);
        setErrors(1);
        return false;
    }

    data.targetColumnCount = data.table.getColumnCount();

    data.b = data.d.createBulkInsert(meta.getTargetDatabase(), meta.getTargetTable(), (short) 0, 0);

    if(meta.getFieldMapping().getNumberOfEntries() == data.targetColumnCount) {
        data.targetInputMapping = new int[meta.getFieldMapping().getNumberOfEntries()];
    }else{
        if(log.isRowLevel()){
            data.d.setDebug((short)0);
        }
        logError("Number of mapping entries and target columns doesn't match");
        setErrors(1);
        return false;
    }

    return true;
  }

  /**
   * Once the transformation starts executing, the processRow() method is called repeatedly
   * by PDI for as long as it returns true. To indicate that a step has finished processing rows
   * this method must call setOutputDone() and return false;
   * 
   * Steps which process incoming rows typically call getRow() to read a single row from the
   * input stream, change or add row content, call putRow() to pass the changed row on 
   * and return true. If getRow() returns null, no more rows are expected to come in, 
   * and the processRow() implementation calls setOutputDone() and returns false to
   * indicate that it is done too.
   * 
   * Steps which generate rows typically construct a new row Object[] using a call to
   * RowDataUtil.allocateRowData(numberOfFields), add row content, and call putRow() to
   * pass the new row on. Above process may happen in a loop to generate multiple rows,
   * at the end of which processRow() would call setOutputDone() and return false;
   * 
   * @param smi the step meta interface containing the step settings
   * @param sdi the step data interface that should be used to store
   * 
   * @return true to indicate that the function should be called again, false if the step is done
   */
  public boolean processRow( StepMetaInterface smi, StepDataInterface sdi ) throws KettleException {

    // safely cast the step settings (meta) and runtime info (data) to specific implementations 
    KettleColumnStoreBulkExporterStepMeta meta = (KettleColumnStoreBulkExporterStepMeta) smi;
    KettleColumnStoreBulkExporterStepData data = (KettleColumnStoreBulkExporterStepData) sdi;

    // get incoming row, getRow() potentially blocks waiting for more rows, returns null if no more rows expected
    Object[] r = getRow();

    // if no more rows are expected, indicate step is finished and processRow() should not be called again
    if ( r == null ) {
      if(log.isRowLevel()){
          data.d.setDebug((short)0);
      }
      setOutputDone();
      return false;
    }

    // the "first" flag is inherited from the base step implementation
    // it is used to guard some processing tasks, like figuring out field indexes
    // in the row structure that only need to be done once
    if ( first ) {
        first = false;
        data.rowMeta = getInputRowMeta().clone();
        data.rowValueTypes = getInputRowMeta().getValueMetaList();

        if(log.isDebug()) {
            logDebug("Input field names and types");
            int g = 0;
            for (String s : data.rowMeta.getFieldNamesAndTypes(16)) {
                logDebug(g++ + " : " + s);
            }

            logDebug("ColumnStore rows and types");
            for (int i = 0; i < data.table.getColumnCount(); i++) {
                logDebug(i + " : " + data.table.getColumn(i).getColumnName() + " : " + data.table.getColumn(i).getType().toString());
            }
        }

        // Construct the targetInputMapping as target[int] mapped to input index int used for bulk import.
        ArrayList<String> inputFields = new ArrayList<>(Arrays.asList(data.rowMeta.getFieldNames()));
        for(int i = 0; i<data.targetColumnCount; i++){
            String mappedInputField = meta.getFieldMapping().getTargetInputMappingField(data.table.getColumn(i).getColumnName());
            data.targetInputMapping[i] = inputFields.indexOf(mappedInputField);
            if(data.targetInputMapping[i]<0){
                data.b.rollback();
                if(log.isRowLevel()){
                    data.d.setDebug((short)0);
                }
                putError(data.rowMeta, r, 1L, "no mapping for column " + data.table.getColumn(i).getColumnName() + " found - rollback", data.rowMeta.getFieldNames()[i], "Column mapping not found");
            }
        }

        // Log the target input mapping
        if(log.isDebug()){
            logDebug("Target Input Mapping:");
            for(int i=0; i<data.targetInputMapping.length; i++){
                logDebug("target: " + i + " - " + data.table.getColumn(i).getColumnName() + " | input: " + data.targetInputMapping[i] + " - " + data.rowMeta.getFieldNames()[data.targetInputMapping[i]]);
            }
        }
    }

    // put the row into ColumnStore
    try {
        logDebug("Iterating through the ColumnStore table to set the row object");
        for (int c = 0; c < data.targetColumnCount; c++) {
        int i = data.targetInputMapping[c];
        boolean nullValue = false;
            if(data.rowValueTypes.get(i).getNativeDataType(r[i]) != null) {
                logDebug("Column " + c + " - " + data.table.getColumn(c).getColumnName() + " -- trying to insert item: " + i + ", class: " + data.rowValueTypes.get(i).getNativeDataType(r[i]).getClass() + ", value to String: " + data.rowValueTypes.get(i).getNativeDataType(r[i]).toString());
            } else{
                nullValue = true;
                logDebug("Trying to insert item of type null from field " + i + ": " + data.rowMeta.getFieldNames()[i] + " into column " + c + ": " + data.table.getColumn(c).getColumnName());
            }
            switch (data.rowValueTypes.get(i).getType()) {
                case TYPE_STRING:
                    logDebug("Try to insert item " + i + " as String");
                    if(nullValue){
                        if(data.table.getColumn(c).isNullable()){
                            data.b.setNull(c);
                        }else{
                            logBasic("Warning: target column is not nullable, using default value for null");
                            data.b.setColumn(c, data.table.getColumn(c).getDefaultValue());
                        }
                    }else {
                        data.b.setColumn(c, data.rowValueTypes.get(i).getString(r[i]));
                    }
                    logDebug("Inserted item " + i + " as String");
                    break;
                case TYPE_INTEGER:
                    logDebug("Try to insert item " + i + " as Long");
                    if(nullValue){
                        if(data.table.getColumn(c).isNullable()){
                            data.b.setNull(c);
                        }else{
                            logBasic("Warning: target column is not nullable, using default value for null");
                            data.b.setColumn(c, data.table.getColumn(c).getDefaultValue());
                        }
                    } else{
                        data.b.setColumn(c, data.rowValueTypes.get(i).getInteger(r[i]));
                    }
                    logDebug("Inserted item " + i + " as Long");
                    break;
                case TYPE_NUMBER:
                    if(nullValue){
                        if(data.table.getColumn(c).isNullable()){
                            data.b.setNull(c);
                        }else{
                            logBasic("Warning: target column is not nullable, using default value for null");
                            data.b.setColumn(c, data.table.getColumn(c).getDefaultValue());
                        }
                    }else{
                        logDebug("Try to insert item " + i + " as Double");
                        data.b.setColumn(c, data.rowValueTypes.get(i).getNumber(r[i]));
                        logDebug("Inserted item " + i + " as Double");
                    }
                    break;
                case TYPE_BIGNUMBER:
                    if(nullValue){
                        if(data.table.getColumn(c).isNullable()){
                            data.b.setNull(c);
                        }else{
                            logBasic("Warning: target column is not nullable, using default value for null");
                            data.b.setColumn(c, data.table.getColumn(c).getDefaultValue());
                        }
                    }else{
                        logDebug("Detect ColumnStore row type");
                        BigDecimal bd = data.rowValueTypes.get(i).getBigNumber(r[i]);
                        if (data.table.getColumn(c).getType() == columnstore_data_types_t.DATA_TYPE_DECIMAL ||
                                data.table.getColumn(c).getType() == columnstore_data_types_t.DATA_TYPE_FLOAT ||
                                data.table.getColumn(c).getType() == columnstore_data_types_t.DATA_TYPE_DOUBLE ||
                                data.table.getColumn(c).getType() == columnstore_data_types_t.DATA_TYPE_UDECIMAL ||
                                data.table.getColumn(c).getType() == columnstore_data_types_t.DATA_TYPE_UFLOAT ||
                                data.table.getColumn(c).getType() == columnstore_data_types_t.DATA_TYPE_UDOUBLE) {
                            logDebug("ColumnStore column is of type 'real'");
                            logDebug("Try to insert item " + i + " as BigDecimal");
                            logDebug("Value to insert: " + bd.toPlainString());
                            data.b.setColumn(c, new ColumnStoreDecimal(bd.toPlainString()));
                            logDebug("Inserted item " + i + " as BigDecimal");
                        } else {
                            logDebug("ColumnStore column is of type 'decimal'");
                            logDebug("Try to insert item " + i + " as BigInteger");
                            logDebug("Value to insert: " + bd.toBigInteger());
                            data.b.setColumn(c, bd.toBigInteger());
                            logDebug("Inserted item " + i + " as BigInteger");
                        }
                    }
                    break;
                case TYPE_DATE:
                    if(nullValue){
                        if(data.table.getColumn(c).isNullable()){
                            data.b.setNull(c);
                        }else{
                            logBasic("Warning: target column is not nullable, using default value for null");
                            data.b.setColumn(c, data.table.getColumn(c).getDefaultValue());
                        }
                    }else{
                        logDebug("Try to insert item " + i + " as Date");
                        Date dt = data.rowValueTypes.get(i).getDate(r[i]);
                        logDebug("Value to insert: " + dateFormat.format(dt));
                        data.b.setColumn(c, dateFormat.format(dt));
                        logDebug("Inserted item " + i + " as Date");
                    }
                    break;
                case TYPE_TIMESTAMP:
                    if(nullValue){
                        if(data.table.getColumn(c).isNullable()){
                            data.b.setNull(c);
                        }else{
                            logBasic("Warning: target column is not nullable, using default value for null");
                            data.b.setColumn(c, data.table.getColumn(c).getDefaultValue());
                        }
                    }else{
                        logDebug("Try to insert item " + i + " as Timestamp");
                        java.sql.Timestamp t;
                        Object nativeData = data.rowValueTypes.get(i).getNativeDataType(r[i]);
                        if (nativeData instanceof java.sql.Timestamp){
                            t = (java.sql.Timestamp) nativeData;
                        } else{
                            t = valueMetaTimestamp.getTimestamp(r[i]);
                        }
                        logDebug("Value to insert: " + t.toString());
                        data.b.setColumn(c, t.toString());
                        logDebug("Inserted item " + i + " as Timestamp");
                    }
                    break;
                case TYPE_BOOLEAN:
                    if(nullValue){
                        if(data.table.getColumn(c).isNullable()){
                            data.b.setNull(c);
                        }else{
                            logBasic("Warning: target column is not nullable, using default value for null");
                            data.b.setColumn(c, data.table.getColumn(c).getDefaultValue());
                        }
                    }else{
                        logDebug("Try to insert item " + i + " as Boolean");
                        boolean b = data.rowValueTypes.get(i).getBoolean(r[i]);
                        data.b.setColumn(c, b);
                        logDebug("Inserted item " + i + " as Boolean");
                    }
                    break;
                case TYPE_BINARY:
                    data.b.rollback();
                    if(log.isRowLevel()){
                        data.d.setDebug((short)0);
                    }
                    putError(data.rowMeta, r, 1L, "data type binary is not supported at the moment - rollback", data.rowMeta.getFieldNames()[i], "Binary data type not supported");
                    setErrors(1);
                default:
                    data.b.rollback();
                    if(log.isRowLevel()){
                        data.d.setDebug((short)0);
                    }
                    putError(data.rowMeta, r, 1L, "data type " + data.rowValueTypes.get(i).getType() + " is not supported at the moment - rollback", data.rowMeta.getFieldNames()[i], "Data type not supported");
                    setErrors(1);
            }
        }
        data.b.writeRow();
    }catch(ColumnStoreException e){
        data.b.rollback();
        if(log.isRowLevel()){
            data.d.setDebug((short)0);
        }
        putError(data.rowMeta, r, 1L, "An error occurred during bulk insert - rollback ", "", e.getMessage());
        setErrors(1);
    }

    // put the row to the output row stream
    putRow( data.rowMeta, r );

    // log progress if it is time to to so
    if ( checkFeedback( getLinesRead() ) ) {
      logBasic( BaseMessages.getString( PKG, "KettleColumnStoreBulkExporterPlugin.Linenr", getLinesRead() ) ); // Some basic logging
    }

    // indicate that processRow() should be called again
    return true;
  }

  /**
   * This method is called by PDI once the step is done processing. 
   * 
   * The dispose() method is the counterpart to init() and should release any resources
   * acquired for step execution like file handles or database connections.
   * 
   * The meta and data implementations passed in can safely be cast
   * to the step's respective implementations. 
   * 
   * It is mandatory that super.dispose() is called to ensure correct behavior.
   * 
   * @param smi  step meta interface implementation, containing the step settings
   * @param sdi  step data interface implementation, used to store runtime information
   */
  public void dispose( StepMetaInterface smi, StepDataInterface sdi ) {

    // Casting to step-specific implementation classes is safe
    KettleColumnStoreBulkExporterStepMeta meta = (KettleColumnStoreBulkExporterStepMeta) smi;
    KettleColumnStoreBulkExporterStepData data = (KettleColumnStoreBulkExporterStepData) sdi;

    // Finally commit the changes to ColumnStore
    try {
        data.b.commit();
        if(log.isRowLevel()){
            data.d.setDebug((short)0);
        }
        logDebug("bulk insert committed");
    }catch(ColumnStoreException e){
        data.b.rollback();
        if(log.isRowLevel()){
            data.d.setDebug((short)0);
        }
        logError("couldn't commit bulk insert to ColumnStore - rollback", e);
        setErrors(1);
    }

  if(log.isDetailed()){
      ColumnStoreSummary summary = data.b.getSummary();
      logDetailed("Execution time: " + summary.getExecutionTime());
      logDetailed("Rows inserted: " + summary.getRowsInsertedCount());
      logDetailed("Truncation count: " + summary.getTruncationCount());
      logDetailed("Saturated count: " + summary.getSaturatedCount());
      logDetailed("Invalid count: " + summary.getInvalidCount());
  }

    if (data.d != null){
        data.d.delete();
    }
    // Call superclass dispose()
    super.dispose( meta, data );
  }
}





