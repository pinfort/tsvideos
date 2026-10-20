INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (1, 'program1', 1, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (2, 'program2', 2, 'ERROR');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (3, 'program3', 3, 'REGISTERED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (4, 'program4', 4, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (5, 'program5', 5, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (6, 'program6', 6, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (7, 'program7', 7, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (8, 'program8', 8, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (9, 'program9', 9, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (10, 'program10', 10, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (11, 'program11', 11, 'COMPLETED');

INSERT INTO `test`.`executed_file`(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status) VALUES(1,'filepath',0,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'SPLITTED');

INSERT INTO `test`.`splitted_file`(id,executed_file_id,file,size,duration,status) VALUES(1,1,'test8',6,7.0,'COMPRESS_SAVED');

INSERT INTO `test`.`created_file`(id,splitted_file_id,file,size,mime,encoding,status) VALUES(1,1,'test',3,'test2','test3','REGISTERED');
