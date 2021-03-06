/*
 * chombo: Hadoop Map Reduce utility
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.chombo.mr;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.chombo.util.Attribute;
import org.chombo.util.AttributeCleanser;
import org.chombo.util.AttributeSchema;
import org.chombo.util.StatsParameters;
import org.chombo.util.Utility;
import org.chombo.validator.InvalidData;
import org.chombo.validator.Validator;
import org.chombo.validator.ValidatorFactory;
import org.codehaus.jackson.map.ObjectMapper;

public class ValidationChecker extends Configured implements Tool {

	@Override
	public int run(String[] args) throws Exception {
        Job job = new Job(getConf());
        String jobName = "ValidationChecker  MR";
        job.setJobName(jobName);
        job.setJarByClass(ValidationChecker.class);

        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        Utility.setConfiguration(job.getConfiguration());
        job.setMapperClass(ValidationChecker.ValidationMapper.class);
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);
        
        //create invalid data report file
        OutputStream os = Utility.getCreateFileOutputStream(job.getConfiguration(), "invalid.data.file.path");
        os.close();
        
        int status =  job.waitForCompletion(true) ? 0 : 1;
        return status;
	}
	
	/**
	 * Mapper for  attribute transformation
	 * @author pranab
	 *
	 */
	public static class ValidationMapper extends Mapper<LongWritable, Text, NullWritable, Text> {
		private Text outVal = new Text();
        private String fieldDelimRegex;
        private String fieldDelimOut;
		private StringBuilder stBld = new  StringBuilder();
        private String[] items;
        private AttributeSchema<Attribute> schema;
        private Map<Integer, List<Validator>> validators = new HashMap<Integer, List<Validator>>();
        private List<InvalidData> invalidDataList = new ArrayList<InvalidData>();
        private boolean filterInvalidRecords;
        private String fieldValue;
        private boolean valid;
        private String invalidDataFilePath;
        private Map<String, Object> validatorContext = new HashMap<String, Object>(); 
        private AttributeSchema<AttributeCleanser> cleanserSchema;
        
        
        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#setup(org.apache.hadoop.mapreduce.Mapper.Context)
         */
        protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration config = context.getConfiguration();
        	fieldDelimRegex = config.get("field.delim.regex", "\\[\\]");
        	fieldDelimOut = config.get("field.delim", ",");
        	filterInvalidRecords = config.getBoolean("filter.invalid.records", true);
        	invalidDataFilePath = config.get("invalid.data.file.path");
        	
        	//schema
        	InputStream is = Utility.getFileStream(config,  "schema.file.path");
        	ObjectMapper mapper = new ObjectMapper();
        	AttributeSchema<Attribute> tempSchema = new AttributeSchema<Attribute>(){};
            schema = mapper.readValue(is, tempSchema.getClass());

            //build validator objects
            String cleanserSchemPath = config.get("cleanser.schema.file.path");
            boolean statsIntialized = false;
            if (null != cleanserSchemPath) {
            	//use data cleanser schema
            	is = Utility.getFileStream(config,  "cleanser.schema.file.path");
            	mapper = new ObjectMapper();
            	AttributeSchema<AttributeCleanser> tempCleanserSchema = new AttributeSchema<AttributeCleanser>(){};
            	cleanserSchema = mapper.readValue(is, tempCleanserSchema.getClass());
            	for (int i : cleanserSchema.getAttributeOrdinals()) {
            		AttributeCleanser attr = cleanserSchema.findAttributeByOrdinal(i);
            		if (attr.isInteger() || attr.isDouble()) {
            			List<Validator> validatorList = new ArrayList<Validator>();  
                		validators.put(i, validatorList);
            			for (String validator : attr.getValidators()) {
	            			if (validator.equals("statBasedRange")) {
	            				if (!statsIntialized) {
	            					getAttributeStats(config, "stat.file.path");
	            					statsIntialized = true;
	            				}
	            				validatorList.add(ValidatorFactory.create(validator, i, schema,validatorContext));
	            			} else {
	            				validatorList.add(ValidatorFactory.create(validator, i, schema,null));
	            			}
            			}
            		}
            	}
            } else {           
            	//use configuration
	            int[] ordinals  = schema.getAttributeOrdinals();
	            for (int ord : ordinals ) {
	            	String key = "validator." + ord;
	            	String validatorString = config.get(key);
	            	if (null != validatorString ) {
	            		List<Validator> validatorList = new ArrayList<Validator>();  
	            		validators.put(ord, validatorList);
	            		String[] valTags = validatorString.split(fieldDelimOut);
	            		for (String valTag :  valTags) {
	            			if (valTag.equals("statBasedRange")) {
	            				if (!statsIntialized) {
	            					getAttributeStats(config, "stat.file.path");
	            					statsIntialized = true;
	            				}
	            				validatorList.add(ValidatorFactory.create(valTag, ord, schema,validatorContext));
	            			} else {
	            				validatorList.add(ValidatorFactory.create(valTag, ord, schema,null));
	            			}
	            		}
	            	}
	            }
            
            }
            
       }
        
        /**
         * @param config
         * @param statsFilePath
         * @throws IOException
         */
        private void getAttributeStats(Configuration config, String statsFilePath) throws IOException {
        	NumericalAttrStatsManager statsManager = new NumericalAttrStatsManager(config, statsFilePath, ",");
        	for (int i : schema.getAttributeOrdinals()) {
        		Attribute attr = schema.findAttributeByOrdinal(i);
        		if (attr.isInteger() || attr.isDouble()) {
        			StatsParameters stats = statsManager.getStatsParameters(i);
        			validatorContext.put("mean:" + i,  stats.getMean());
					validatorContext.put("stdDev:" + i,  stats.getStdDev());
        		}
        	}
        }
        
        @Override
		protected void cleanup(Context context) throws IOException,
				InterruptedException {
			super.cleanup(context);
        	Configuration config = context.getConfiguration();
            OutputStream os = Utility.getAppendFileOutputStream(config, "invalid.data.file.path");
			
            for (InvalidData invalidData : invalidDataList ) {
            	byte[] data = invalidData.toString().getBytes();
            	os.write(data);
            }
            os.flush();
            os.close();
		}


		/* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#map(KEYIN, VALUEIN, org.apache.hadoop.mapreduce.Mapper.Context)
         */
        @Override
        protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
            items  =  value.toString().split(fieldDelimRegex);
            stBld.delete(0, stBld.length());
            valid = true;
            InvalidData invalidData = null;
            for (int i = 0; i < items.length; ++i) {
            	List<Validator> validatorList = validators.get(i);
            	fieldValue = items[i]; 
            	if(null != validatorList) {
            		for (Validator validator : validatorList) {
            			valid = validator.isValid(fieldValue);
            			if (!valid) {
            				if (null == invalidData) {
            					invalidData = new InvalidData(value.toString());
            					invalidDataList.add(invalidData);
            				}
            				invalidData.addValidationFailure(i, validator.getTag());
            			}
            		}
            	}
            }
            
            if (valid || !filterInvalidRecords ) {
            	outVal.set(value.toString());
            	context.write(NullWritable.get(), outVal);
            }
            
        }
	}	

	
	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new ValidationChecker(), args);
        System.exit(exitCode);
	}
	
}
