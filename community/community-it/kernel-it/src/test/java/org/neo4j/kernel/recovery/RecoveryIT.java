/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.io.ByteUnit;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.OpenMode;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.ReadableLogChannel;
import org.neo4j.kernel.impl.transaction.log.entry.CheckPoint;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntry;
import org.neo4j.kernel.impl.transaction.log.entry.VersionAwareLogEntryReader;
import org.neo4j.kernel.impl.transaction.log.files.LogFile;
import org.neo4j.kernel.impl.transaction.log.files.LogFiles;
import org.neo4j.kernel.impl.transaction.log.files.LogFilesBuilder;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.neo4j.test.extension.DefaultFileSystemExtension;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.TestDirectoryExtension;
import org.neo4j.test.rule.TestDirectory;

import static java.lang.String.valueOf;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.configuration.Config.defaults;
import static org.neo4j.graphdb.RelationshipType.withName;
import static org.neo4j.helpers.collection.Iterables.count;
import static org.neo4j.kernel.recovery.Recovery.isRecoveryRequired;
import static org.neo4j.kernel.recovery.Recovery.performRecovery;

@ExtendWith( {DefaultFileSystemExtension.class, TestDirectoryExtension.class} )
class RecoveryIT
{
    private static final int TEN_KB = (int) ByteUnit.kibiBytes( 10 );
    @Inject
    private DefaultFileSystemAbstraction fileSystem;
    @Inject
    private TestDirectory directory;

    @Test
    void recoveryRequiredOnDatabaseWithoutCorrectCheckpoints() throws Exception
    {
        GraphDatabaseService database = createDatabase();
        generateSomeData( database );
        database.shutdown();
        removeLastCheckpointRecordFromLastLogFile();

        assertTrue( isRecoveryRequired( fileSystem, directory.databaseLayout(), defaults() ) );
    }

    @Test
    void recoveryNotRequiredWhenDatabaseNotFound() throws Exception
    {
        DatabaseLayout absentDatabase = directory.databaseLayout( "absent" );
        assertFalse( isRecoveryRequired( fileSystem, absentDatabase, defaults() ) );
    }

    @Test
    void recoverEmptyDatabase() throws Exception
    {
        GraphDatabaseService database = createDatabase();
        database.shutdown();
        removeLastCheckpointRecordFromLastLogFile();

        recoverDatabase();
    }

    @Test
    void recoverDatabaseWithNodes() throws Exception
    {
        GraphDatabaseService database = createDatabase();

        int numberOfNodes = 10;
        for ( int i = 0; i < numberOfNodes; i++ )
        {
            createSingleNode( database );
        }
        database.shutdown();
        removeLastCheckpointRecordFromLastLogFile();

        recoverDatabase();

        GraphDatabaseService recoveredDatabase = createDatabase();
        try ( Transaction ignore = recoveredDatabase.beginTx() )
        {
            assertEquals( numberOfNodes, count( recoveredDatabase.getAllNodes() ) );
        }
        finally
        {
            recoveredDatabase.shutdown();
        }
    }

    @Test
    void recoverDatabaseWithNodesAndRelationshipsAndRelationshipTypes() throws Exception
    {
        GraphDatabaseService database = createDatabase();

        int numberOfRelationships = 10;
        int numberOfNodes = numberOfRelationships * 2;
        for ( int i = 0; i < numberOfRelationships; i++ )
        {
            try ( Transaction transaction = database.beginTx() )
            {
                Node start = database.createNode();
                Node stop = database.createNode();
                start.createRelationshipTo( stop, withName( valueOf( i ) ) );
                transaction.success();
            }
        }
        database.shutdown();
        removeLastCheckpointRecordFromLastLogFile();

        recoverDatabase();

        GraphDatabaseService recoveredDatabase = createDatabase();
        try ( Transaction ignore = recoveredDatabase.beginTx() )
        {
            assertEquals( numberOfNodes, count( recoveredDatabase.getAllNodes() ) );
            assertEquals( numberOfRelationships, count( recoveredDatabase.getAllRelationships() ) );
            assertEquals( numberOfRelationships, count( recoveredDatabase.getAllRelationshipTypesInUse() ) );
        }
        finally
        {
            recoveredDatabase.shutdown();
        }
    }

    @Test
    void recoverDatabaseWithProperties() throws Exception
    {
        GraphDatabaseService database = createDatabase();

        int numberOfRelationships = 10;
        int numberOfNodes = numberOfRelationships * 2;
        for ( int i = 0; i < numberOfRelationships; i++ )
        {
            try ( Transaction transaction = database.beginTx() )
            {
                Node start = database.createNode();
                Node stop = database.createNode();
                start.setProperty( "start" + i, i );
                stop.setProperty( "stop" + i, i );
                start.createRelationshipTo( stop, withName( valueOf( i ) ) );
                transaction.success();
            }
        }
        database.shutdown();
        removeLastCheckpointRecordFromLastLogFile();

        recoverDatabase();

        GraphDatabaseService recoveredDatabase = createDatabase();
        try ( Transaction ignore = recoveredDatabase.beginTx() )
        {
            assertEquals( numberOfNodes, count( recoveredDatabase.getAllNodes() ) );
            assertEquals( numberOfRelationships, count( recoveredDatabase.getAllRelationships() ) );
            assertEquals( numberOfRelationships, count( recoveredDatabase.getAllRelationshipTypesInUse() ) );
            assertEquals( numberOfNodes, count( recoveredDatabase.getAllPropertyKeys() ) );
        }
        finally
        {
            recoveredDatabase.shutdown();
        }
    }

    @Test
    void recoverDatabaseWithIndex() throws Exception
    {
        GraphDatabaseService database = createDatabase();

        int numberOfRelationships = 10;
        int numberOfNodes = numberOfRelationships * 2;
        String startProperty = "start";
        String stopProperty = "stop";
        Label startMarker = Label.label( "start" );
        Label stopMarker = Label.label( "stop" );

        try ( Transaction transaction = database.beginTx() )
        {
            database.schema().indexFor( startMarker ).on( startProperty ).create();
            database.schema().constraintFor( stopMarker ).assertPropertyIsUnique( stopProperty ).create();
            transaction.success();
        }
        try ( Transaction transaction = database.beginTx() )
        {
            database.schema().awaitIndexesOnline( 1, TimeUnit.MINUTES );
            transaction.success();
        }

        for ( int i = 0; i < numberOfRelationships; i++ )
        {
            try ( Transaction transaction = database.beginTx() )
            {
                Node start = database.createNode( startMarker );
                Node stop = database.createNode( stopMarker );

                start.setProperty( startProperty, i );
                stop.setProperty( stopProperty, i );
                start.createRelationshipTo( stop, withName( valueOf( i ) ) );
                transaction.success();
            }
        }
        long numberOfPropertyKeys;
        try ( Transaction ignore = database.beginTx() )
        {
            numberOfPropertyKeys = count( database.getAllPropertyKeys() );
        }
        database.shutdown();
        removeLastCheckpointRecordFromLastLogFile();

        recoverDatabase();

        GraphDatabaseService recoveredDatabase = createDatabase();
        try ( Transaction ignore = recoveredDatabase.beginTx() )
        {
            assertEquals( numberOfNodes, count( recoveredDatabase.getAllNodes() ) );
            assertEquals( numberOfRelationships, count( recoveredDatabase.getAllRelationships() ) );
            assertEquals( numberOfRelationships, count( recoveredDatabase.getAllRelationshipTypesInUse() ) );
            assertEquals( numberOfPropertyKeys, count( recoveredDatabase.getAllPropertyKeys() ) );
        }
        finally
        {
            recoveredDatabase.shutdown();
        }
    }

    @Test
    void recoverDatabaseWithFirstTransactionLogFileWithoutShutdownCheckpoint() throws Exception
    {
        GraphDatabaseService database = createDatabase();
        generateSomeData( database );
        database.shutdown();
        assertEquals( 1, countCheckPointsInTransactionLogs() );
        removeLastCheckpointRecordFromLastLogFile();

        assertEquals( 0, countCheckPointsInTransactionLogs() );
        assertTrue( isRecoveryRequired( fileSystem, directory.databaseLayout(), defaults() ) );

        startStopDatabase();

        assertFalse( isRecoveryRequired( fileSystem, directory.databaseLayout(), defaults() ) );
        // we will have 2 checkpoints: first will be created after successful recovery and another on shutdown
        assertEquals( 2, countCheckPointsInTransactionLogs() );
    }

    @Test
    void startDatabaseWithRemovedSingleTransactionLogFile() throws Exception
    {
        GraphDatabaseService database = createDatabase();
        generateSomeData( database );
        database.shutdown();

        removeTransactionLogs();

        startStopDatabase();

        assertFalse( isRecoveryRequired( fileSystem, directory.databaseLayout(), defaults() ) );
        // we will have 2 checkpoints: first will be created as part of recovery and another on shutdown
        assertEquals( 2, countCheckPointsInTransactionLogs() );
    }

    @Test
    void startDatabaseWithRemovedMultipleTransactionLogFiles() throws Exception
    {
        GraphDatabaseService database = new TestGraphDatabaseFactory()
                .newEmbeddedDatabaseBuilder( directory.databaseDir() )
                .setConfig( GraphDatabaseSettings.logical_log_rotation_threshold, "1M" )
                .newGraphDatabase();
        while ( countTransactionLogFiles() < 5 )
        {
            generateSomeData( database );
        }
        database.shutdown();

        removeTransactionLogs();

        startStopDatabase();

        assertFalse( isRecoveryRequired( fileSystem, directory.databaseLayout(), defaults() ) );
        // we will have 2 checkpoints: first will be created as part of recovery and another on shutdown
        assertEquals( 2, countCheckPointsInTransactionLogs() );
    }

    @Test
    void killAndStartDatabaseAfterTransactionLogsRemoval() throws Exception
    {
        GraphDatabaseService database = new TestGraphDatabaseFactory()
                .newEmbeddedDatabaseBuilder( directory.databaseDir() )
                .setConfig( GraphDatabaseSettings.logical_log_rotation_threshold, "1M" )
                .newGraphDatabase();
        while ( countTransactionLogFiles() < 5 )
        {
            generateSomeData( database );
        }
        database.shutdown();

        removeTransactionLogs();
        assertTrue( isRecoveryRequired( fileSystem, directory.databaseLayout(), defaults() ) );
        assertEquals( 0, countTransactionLogFiles() );

        GraphDatabaseService service = createDatabase();
        createSingleNode( service );
        service.shutdown();

        assertEquals( 1, countTransactionLogFiles() );
        assertEquals( 2, countCheckPointsInTransactionLogs() );
        removeLastCheckpointRecordFromLastLogFile();

        startStopDatabase();

        assertFalse( isRecoveryRequired( fileSystem, directory.databaseLayout(), defaults() ) );
        // we will have 3 checkpoints: one from logs before recovery, second will be created as part of recovery and another on shutdown
        assertEquals( 3, countCheckPointsInTransactionLogs() );
    }

    @Test
    void killAndStartDatabaseAfterTransactionLogsRemovalWithSeveralFilesWithoutCheckpoint() throws Exception
    {
        GraphDatabaseService database = new TestGraphDatabaseFactory()
                .newEmbeddedDatabaseBuilder( directory.databaseDir() )
                .setConfig( GraphDatabaseSettings.logical_log_rotation_threshold, "1M" )
                .newGraphDatabase();
        while ( countTransactionLogFiles() < 5 )
        {
            generateSomeData( database );
        }
        database.shutdown();

        removeHighestLogFile();

        assertEquals( 4, countTransactionLogFiles() );
        assertEquals( 0, countCheckPointsInTransactionLogs() );
        assertTrue( isRecoveryRequired( fileSystem, directory.databaseLayout(), defaults() ) );

        startStopDatabase();
        assertEquals( 2, countCheckPointsInTransactionLogs() );
        removeLastCheckpointRecordFromLastLogFile();
        removeLastCheckpointRecordFromLastLogFile();

        startStopDatabase();

        assertFalse( isRecoveryRequired( fileSystem, directory.databaseLayout(), defaults() ) );
        // we will have 2 checkpoints: first will be created as part of recovery and another on shutdown
        assertEquals( 2, countCheckPointsInTransactionLogs() );
    }

    @Test
    void startDatabaseAfterTransactionLogsRemovalAndKillAfterRecovery() throws Exception
    {
        GraphDatabaseService database = new TestGraphDatabaseFactory()
                .newEmbeddedDatabaseBuilder( directory.databaseDir() )
                .setConfig( GraphDatabaseSettings.logical_log_rotation_threshold, "1M" )
                .newGraphDatabase();
        while ( countTransactionLogFiles() < 5 )
        {
            generateSomeData( database );
        }
        database.shutdown();

        removeHighestLogFile();

        assertEquals( 4, countTransactionLogFiles() );
        assertEquals( 0, countCheckPointsInTransactionLogs() );
        assertTrue( isRecoveryRequired( fileSystem, directory.databaseLayout(), defaults() ) );

        startStopDatabase();
        assertEquals( 2, countCheckPointsInTransactionLogs() );
        removeLastCheckpointRecordFromLastLogFile();

        startStopDatabase();

        assertFalse( isRecoveryRequired( fileSystem, directory.databaseLayout(), defaults() ) );
        // we will have 2 checkpoints here because offset in both of them will be the same and 2 will be truncated instead since truncation is based on position
        // next start-stop cycle will have transaction between so we will have 3 checkpoints as expected.
        assertEquals( 2, countCheckPointsInTransactionLogs() );
        removeLastCheckpointRecordFromLastLogFile();

        GraphDatabaseService service = createDatabase();
        createSingleNode( service );
        service.shutdown();
        removeLastCheckpointRecordFromLastLogFile();
        startStopDatabase();

        assertFalse( isRecoveryRequired( fileSystem, directory.databaseLayout(), defaults() ) );
        assertEquals( 3, countCheckPointsInTransactionLogs() );
    }

    private void createSingleNode( GraphDatabaseService service )
    {
        try ( Transaction transaction = service.beginTx() )
        {
            service.createNode();
            transaction.success();
        }
    }

    private void startStopDatabase()
    {
        createDatabase().shutdown();
    }

    private void recoverDatabase() throws Exception
    {
        DatabaseLayout databaseLayout = directory.databaseLayout();
        assertTrue( isRecoveryRequired( databaseLayout, defaults() ) );
        performRecovery( databaseLayout );
        assertFalse( isRecoveryRequired( databaseLayout, defaults() ) );
    }

    private int countCheckPointsInTransactionLogs() throws IOException
    {
        int checkpointCounter = 0;

        LogFiles logFiles = buildLogFiles();
        LogFile transactionLogFile = logFiles.getLogFile();
        VersionAwareLogEntryReader<ReadableLogChannel> entryReader = new VersionAwareLogEntryReader<>();
        LogPosition startPosition = LogPosition.start( logFiles.getHighestLogVersion() );
        try ( ReadableLogChannel reader = transactionLogFile.getReader( startPosition ) )
        {
            LogEntry logEntry;
            do
            {
                logEntry = entryReader.readLogEntry( reader );
                if ( logEntry instanceof CheckPoint )
                {
                    checkpointCounter++;
                }
            }
            while ( logEntry != null );
        }
        return checkpointCounter;
    }

    private LogFiles buildLogFiles() throws IOException
    {
        return LogFilesBuilder.logFilesBasedOnlyBuilder( directory.databaseDir(), fileSystem ).build();
    }

    private void removeTransactionLogs() throws IOException
    {
        LogFiles logFiles = buildLogFiles();
        File[] txLogFiles = logFiles.logFiles();
        for ( File logFile : txLogFiles )
        {
            fileSystem.deleteFile( logFile );
        }
    }

    private void removeHighestLogFile() throws IOException
    {
        LogFiles logFiles = buildLogFiles();
        long highestLogVersion = logFiles.getHighestLogVersion();
        removeFileByVersion( logFiles, highestLogVersion );
    }

    private void removeFileByVersion( LogFiles logFiles, long version )
    {
        File versionFile = logFiles.getLogFileForVersion( version );
        assertNotNull( versionFile );
        fileSystem.deleteFile( versionFile );
    }

    private int countTransactionLogFiles() throws IOException
    {
        LogFiles logFiles = buildLogFiles();
        return logFiles.logFiles().length;
    }

    private void removeLastCheckpointRecordFromLastLogFile() throws IOException
    {
        LogPosition checkpointPosition = null;

        LogFiles logFiles = buildLogFiles();
        LogFile transactionLogFile = logFiles.getLogFile();
        VersionAwareLogEntryReader<ReadableLogChannel> entryReader = new VersionAwareLogEntryReader<>();
        LogPosition startPosition = LogPosition.start( logFiles.getHighestLogVersion() );
        try ( ReadableLogChannel reader = transactionLogFile.getReader( startPosition ) )
        {
            LogEntry logEntry;
            do
            {
                logEntry = entryReader.readLogEntry( reader );
                if ( logEntry instanceof CheckPoint )
                {
                    checkpointPosition = ((CheckPoint) logEntry).getLogPosition();
                }
            }
            while ( logEntry != null );
        }
        if ( checkpointPosition != null )
        {
            try ( StoreChannel storeChannel = fileSystem.open( logFiles.getHighestLogFile(), OpenMode.READ_WRITE ) )
            {
                storeChannel.truncate( checkpointPosition.getByteOffset() );
            }
        }
    }

    private static void generateSomeData( GraphDatabaseService database )
    {
        for ( int i = 0; i < 10; i++ )
        {
            try ( Transaction transaction = database.beginTx() )
            {
                Node node1 = database.createNode();
                Node node2 = database.createNode();
                node1.createRelationshipTo( node2, withName( "Type" + i ) );
                node2.setProperty( "a", randomAlphanumeric( TEN_KB ) );
                transaction.success();
            }
        }
    }

    private GraphDatabaseService createDatabase()
    {
        return new TestGraphDatabaseFactory().newEmbeddedDatabase( directory.databaseDir() );
    }
}
