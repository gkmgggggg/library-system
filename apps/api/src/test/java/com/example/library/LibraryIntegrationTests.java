package com.example.library;
import com.example.library.auth.*;
import com.example.library.catalog.*;
import com.example.library.common.*;
import com.example.library.circulation.*;
import com.example.library.readers.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:1/library_test}",
 "spring.datasource.username=${TEST_DATABASE_USER:library_test}","spring.datasource.password=${TEST_DATABASE_PASSWORD:}"})
class LibraryIntegrationTests {
 static final java.nio.file.Path COVER_DIR=createCoverDirectory();
 static java.nio.file.Path createCoverDirectory() { try{return java.nio.file.Files.createTempDirectory("library-test-covers-");}catch(Exception e){throw new RuntimeException(e);} }
 @DynamicPropertySource static void coverDirectory(DynamicPropertyRegistry registry) { registry.add("library.cover-dir",()->COVER_DIR.toString()); }
 @AfterAll static void removeCoverDirectory() throws Exception { try(var files=java.nio.file.Files.walk(COVER_DIR)){for(var file:files.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(file);} }
 @Autowired Sql sql;@Autowired CirculationService circulation;@Autowired CatalogService catalog;@Autowired ReaderService readers;
 @Autowired CatalogImportService catalogImports;
 @Autowired CatalogMaintenanceService maintenance;@Autowired BookCoverService coverService;
 @Autowired PasswordEncoder passwords;@Autowired TransactionTemplate tx;@Autowired Environment environment;@Autowired ObjectMapper json;
 UUID adminId,readerId,readerUser,otherId,otherUser,bookId,categoryId;List<UUID> copies;String password;
 @BeforeEach void fixture() {
  assertTrue(environment.getProperty("spring.datasource.url","").endsWith("/library_test"),"必须使用测试数据库");
  sql.update("TRUNCATE users,categories,books,book_copies,loans,loan_renewals,sessions,idempotency_keys RESTART IDENTITY CASCADE",Map.of());
  password=AuthService.randomToken();String hash=passwords.encode(password);
  adminId=id(sql.one("INSERT INTO users(username,password_hash,role,must_change_password) VALUES('test_admin',:hash,'admin',false) RETURNING id",Sql.params("hash",hash)));
  readerUser=id(sql.one("INSERT INTO users(username,password_hash,role,must_change_password) VALUES('test_reader',:hash,'reader',false) RETURNING id",Sql.params("hash",hash)));
  otherUser=id(sql.one("INSERT INTO users(username,password_hash,role,must_change_password) VALUES('other_reader',:hash,'reader',false) RETURNING id",Sql.params("hash",hash)));
  readerId=id(sql.one("INSERT INTO readers(user_id,name) VALUES(:user,'测试读者') RETURNING id",Sql.params("user",readerUser)));
  otherId=id(sql.one("INSERT INTO readers(user_id,name) VALUES(:user,'其他读者') RETURNING id",Sql.params("user",otherUser)));
  categoryId=id(sql.one("INSERT INTO categories(name) VALUES('测试分类') RETURNING id",Map.of()));
  bookId=id(sql.one("INSERT INTO books(title,author,category_id) VALUES('测试书目','测试作者',:category) RETURNING id",Sql.params("category",categoryId)));
  copies=sql.list("INSERT INTO book_copies(book_id) SELECT :book FROM generate_series(1,8) RETURNING id",Sql.params("book",bookId)).stream().map(LibraryIntegrationTests::id).toList();
  asAdmin();
 }
 @AfterEach void cleanupAuth() { SecurityContextHolder.clearContext(); }
 static UUID id(Map<String,Object> row) { return UUID.fromString((String)row.get("id")); }
 void asAdmin() { auth(new CurrentUser(adminId,"test_admin","admin",false)); }
 static void auth(CurrentUser user) { SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user,null,List.of())); }
 Map<String,Object> borrow(UUID reader,UUID copy) { return circulation.borrow(UUID.randomUUID(),new CirculationController.Borrow(reader,copy,"")); }
 long count(String query) { return sql.count(query,Map.of()); }
 UUID demoBook() {
  return id(sql.one("INSERT INTO books(category_id,title,author,description) VALUES(:category,'数据库入门练习','学习项目编写组','仅用于本地学习的虚构书目') RETURNING id",Sql.params("category",categoryId)));
 }
 @Test void samplePreparationDeletesOnlyUnusedDemoAndAddsOneCopyIdempotently() {
  UUID demo=demoBook();sql.update("INSERT INTO book_copies(book_id) SELECT :id FROM generate_series(1,3)",Sql.params("id",demo));
  catalogImports.importDocument(new CatalogImportService.Document("fanqie",List.of(importedBook("7000000000000000020","来源小说","网络小说"))));
  var result=maintenance.prepareFanqieSamples();assertEquals(1,result.deletedBooks());assertEquals(3,result.deletedCopies());assertEquals(1,result.addedCopies());
  assertEquals(2,count("SELECT count(*) FROM books"));assertEquals(9,count("SELECT count(*) FROM book_copies"));assertEquals(3,count("SELECT count(*) FROM users"));
  var again=maintenance.prepareFanqieSamples();assertEquals(0,again.deletedBooks());assertEquals(0,again.addedCopies());assertEquals(1,again.skippedBooks());
  assertEquals(1,count("SELECT count(*) FROM book_copies WHERE note LIKE '学习用模拟副本%'") );
 }
 @Test void sampleCleanupRefusesEvenReturnedLoanHistoryAndRollsBack() {
  UUID demo=demoBook();UUID copy=id(sql.one("INSERT INTO book_copies(book_id) VALUES(:id) RETURNING id",Sql.params("id",demo)));
  UUID loan=id(borrow(readerId,copy));circulation.giveBack(UUID.randomUUID(),loan,new CirculationController.Return(false,""));
  assertThrows(IllegalStateException.class,()->maintenance.prepareFanqieSamples());
  assertEquals(2,count("SELECT count(*) FROM books"));assertEquals(9,count("SELECT count(*) FROM book_copies"));assertEquals(1,count("SELECT count(*) FROM loans"));
 }
 @Test void samplePreparationKeepsArchivedSourcesWithoutCreatingCopies() {
  catalogImports.importDocument(new CatalogImportService.Document("fanqie",List.of(importedBook("7000000000000000021","归档来源","网络小说"))));
  sql.update("UPDATE books SET archived_at=now() WHERE title='归档来源'",Map.of());
  var result=maintenance.prepareFanqieSamples();assertEquals(0,result.addedCopies());assertEquals(1,result.skippedBooks());
  var distributed=maintenance.distributeFanqieCopies();assertEquals(0,distributed.addedCopies());assertEquals(1,distributed.skippedBooks());
 }
 @Test void variedInventoryUsesSeveralQuantitiesAndDoesNotGrowOnRerun() {
  var sources=List.of(importedBook("7000000000000000031","检修样本","网络小说"),importedBook("7000000000000000038","六册样本","网络小说"),importedBook("7000000000000000053","五册样本","网络小说"));
  catalogImports.importDocument(new CatalogImportService.Document("fanqie",sources));maintenance.prepareFanqieSamples();
  var result=maintenance.distributeFanqieCopies();assertEquals(3,result.processedBooks());assertEquals(3,result.relocatedCopies());assertTrue(result.addedCopies()>0);
  assertEquals(1,result.maintenanceCopies());assertEquals(1,count("SELECT count(*) FROM book_copies WHERE status='unavailable' AND status_reason IS NOT NULL"));
  assertEquals(6,count("SELECT count(*) FROM book_copies bc JOIN book_sources s ON s.book_id=bc.book_id WHERE s.external_id='7000000000000000038'"));
  assertEquals(5,count("SELECT count(*) FROM book_copies bc JOIN book_sources s ON s.book_id=bc.book_id WHERE s.external_id='7000000000000000053'"));
  assertEquals(8,sql.count("SELECT count(*) FROM book_copies WHERE book_id=:id AND location=''",Sql.params("id",bookId)));
  var again=maintenance.distributeFanqieCopies();assertEquals(0,again.addedCopies());assertEquals(0,again.relocatedCopies());assertEquals(result.totalCopies(),again.totalCopies());
 }
 @Test void inventoryDistributionPreservesCirculationAndRetiredCopies() {
  catalogImports.importDocument(new CatalogImportService.Document("fanqie",List.of(importedBook("7000000000000000038","历史样本","网络小说"))));maintenance.prepareFanqieSamples();
  UUID sourceBook=id(sql.one("SELECT book_id AS id FROM book_sources WHERE external_id='7000000000000000038'",Map.of()));
  UUID oldCopy=id(sql.one("SELECT id FROM book_copies WHERE book_id=:id",Sql.params("id",sourceBook)));
  UUID loan=id(borrow(readerId,oldCopy));circulation.giveBack(UUID.randomUUID(),loan,new CirculationController.Return(false,""));
  var result=maintenance.distributeFanqieCopies();assertEquals(5,result.addedCopies());assertEquals(0,result.relocatedCopies());
  assertEquals("学习库 / 番茄小说",sql.one("SELECT location FROM book_copies WHERE id=:id",Sql.params("id",oldCopy)).get("location"));
  UUID withdrawn=id(sql.one("SELECT id FROM book_copies WHERE book_id=:id AND id<>:old AND status='available' LIMIT 1",Sql.params("id",sourceBook,"old",oldCopy)));
  catalog.copyState(withdrawn,new CatalogController.CopyState("retired","练习退出流通","手工位置","手工备注"));
  assertEquals(0,maintenance.distributeFanqieCopies().addedCopies());
  assertEquals("retired",sql.one("SELECT status FROM book_copies WHERE id=:id",Sql.params("id",withdrawn)).get("status"));
  assertEquals(1,count("SELECT count(*) FROM loans WHERE returned_at IS NOT NULL"));
 }
 @Test void inventoryDistributionRollsBackRelocationAndCopiesTogether() {
  catalogImports.importDocument(new CatalogImportService.Document("fanqie",List.of(importedBook("7000000000000000038","回滚样本","网络小说"))));maintenance.prepareFanqieSamples();
  sql.update("CREATE FUNCTION test_inventory_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.note LIKE '%馆藏分布 v1%' THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$",Map.of());
  sql.update("CREATE TRIGGER test_inventory_fail BEFORE INSERT ON book_copies FOR EACH ROW EXECUTE FUNCTION test_inventory_fail()",Map.of());
  try {
   assertThrows(org.springframework.dao.DataAccessException.class,()->maintenance.distributeFanqieCopies());
   assertEquals(9,count("SELECT count(*) FROM book_copies"));assertEquals(1,count("SELECT count(*) FROM book_copies WHERE location='学习库 / 番茄小说'"));
  } finally {sql.update("DROP TRIGGER test_inventory_fail ON book_copies",Map.of());sql.update("DROP FUNCTION test_inventory_fail()",Map.of());}
 }
 BookCoverService.Cover coverFixture(String external) throws Exception {
  var buffer=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",buffer);
  byte[] bytes=buffer.toByteArray();String filename=external+".png";java.nio.file.Files.write(COVER_DIR.resolve(filename),bytes);
  return new BookCoverService.Cover(external,filename,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.length,"novel-pic/ai/testimage","2026-10-12T00:00:00Z");
 }
 @Test void coversServeLocalImagesWithAuthenticationAndArchivePermissions() throws Exception {
  String external="7000000000000000022";
  catalogImports.importDocument(new CatalogImportService.Document("fanqie",List.of(importedBook(external,"封面来源","网络小说"))));
  var cover=coverFixture(external);var manifest=new BookCoverService.Document("fanqie",List.of(cover),List.of());
  assertEquals(1,coverService.importCovers(manifest));assertEquals(0,coverService.importCovers(manifest));
  UUID id=id(sql.one("SELECT book_id AS id FROM book_sources WHERE external_id=:id",Sql.params("id",external)));
  assertTrue(catalog.book(id).get("coverUrl").toString().contains("/books/"+id+"/cover?v="));
  var browser=browser();assertEquals(401,request(browser,"/books/"+id+"/cover","GET",null,false).statusCode());
  assertEquals(200,request(browser,"/auth/login","POST",Map.of("username","test_reader","password",password),true).statusCode());
  String url="http://127.0.0.1:"+environment.getProperty("local.server.port")+"/api/v1/books/"+id+"/cover";
  var response=browser.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
  assertEquals(200,response.statusCode());assertEquals("image/png",response.headers().firstValue("Content-Type").orElse(""));
  assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("private"));
  assertArrayEquals(java.nio.file.Files.readAllBytes(COVER_DIR.resolve(cover.filename())),response.body());
  catalog.archive(id,true);assertEquals(404,request(browser,"/books/"+id+"/cover","GET",null,false).statusCode());
 }
 @Test void coverImportRejectsPathsAndCorruptionAndRollsBackMissingSources() throws Exception {
  String external="7000000000000000023";
  catalogImports.importDocument(new CatalogImportService.Document("fanqie",List.of(importedBook(external,"验证来源","网络小说"))));
  var good=coverFixture(external);var bad=new BookCoverService.Cover(external,"../../private.png",good.sha256(),good.size(),good.coverUri(),good.fetchedAt());
  assertThrows(IllegalArgumentException.class,()->coverService.importCovers(new BookCoverService.Document("fanqie",List.of(bad),List.of())));
  var corrupt=new BookCoverService.Cover(external,good.filename(),"0".repeat(64),good.size(),good.coverUri(),good.fetchedAt());
  assertThrows(IllegalArgumentException.class,()->coverService.importCovers(new BookCoverService.Document("fanqie",List.of(corrupt),List.of())));
  var missing=coverFixture("7000000000000000024");
  assertThrows(BusinessException.class,()->coverService.importCovers(new BookCoverService.Document("fanqie",List.of(good,missing),List.of())));
  assertEquals(0,count("SELECT count(*) FROM books WHERE cover_file IS NOT NULL"));
 }
 CatalogImportService.Book importedBook(String external, String title, String category) {
  return new CatalogImportService.Book(external,title,"作者' SQL",category,"公开书目信息概览","https://fanqienovel.com/page/"+external,"2026-10-12T00:00:00Z",List.of("玄幻"),"连载中");
 }
 @Test void catalogueImportDeduplicatesWithoutInventoryAndPreservesEdits() {
  var book=importedBook("7000000000000000001","标题'; DROP TABLE users; --","网络小说");
  var document=new CatalogImportService.Document("fanqie",List.of(book));
  var first=catalogImports.importDocument(document);
  assertEquals(1,first.inserted());assertEquals(0,first.skipped());
  var row=sql.one("SELECT b.id,b.title,b.isbn,b.publisher,b.publication_year FROM books b JOIN book_sources s ON s.book_id=b.id WHERE s.external_id=:external",Sql.params("external",book.externalId()));
  assertEquals(book.title(),row.get("title"));assertNull(row.get("isbn"));assertNull(row.get("publisher"));assertNull(row.get("publication_year"));
  UUID importedId=id(row);
  assertEquals(0,sql.count("SELECT count(*) FROM book_copies WHERE book_id=:book",Sql.params("book",importedId)));
  sql.update("UPDATE books SET title='人工编辑' WHERE id=:id",Sql.params("id",importedId));
  var repeated=catalogImports.importDocument(document);
  assertEquals(0,repeated.inserted());assertEquals(1,repeated.skipped());
  assertEquals("人工编辑",sql.one("SELECT title FROM books WHERE id=:id",Sql.params("id",importedId)).get("title"));
  assertEquals(3,count("SELECT count(*) FROM users"));
 }
 @Test void catalogueImportRejectsInvalidBatchBeforeWriting() {
  long books=count("SELECT count(*) FROM books"),batches=count("SELECT count(*) FROM catalog_import_batches");
  var good=importedBook("7000000000000000002","有效标题","网络小说");
  var bad=importedBook("7000000000000000003","\ue437乱码","网络小说");
  assertThrows(IllegalArgumentException.class,()->catalogImports.importDocument(new CatalogImportService.Document("fanqie",List.of(good,bad))));
  assertThrows(IllegalArgumentException.class,()->catalogImports.importDocument(new CatalogImportService.Document("fanqie",List.of(good,good))));
  assertEquals(books,count("SELECT count(*) FROM books"));assertEquals(batches,count("SELECT count(*) FROM catalog_import_batches"));
 }
 @Test void catalogueImportRollsBackEntireBatchOnDatabaseFailure() {
  long books=count("SELECT count(*) FROM books"),batches=count("SELECT count(*) FROM catalog_import_batches"),categories=count("SELECT count(*) FROM categories");
  sql.update("CREATE FUNCTION test_reject_import() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.external_id='7000000000000000005' THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$",Map.of());
  sql.update("CREATE TRIGGER test_reject_import BEFORE INSERT ON book_sources FOR EACH ROW EXECUTE FUNCTION test_reject_import()",Map.of());
  try {
   var first=importedBook("7000000000000000004","第一本","导入事务测试");
   var second=importedBook("7000000000000000005","第二本","导入事务测试");
   assertThrows(org.springframework.dao.DataAccessException.class,()->catalogImports.importDocument(new CatalogImportService.Document("fanqie",List.of(first,second))));
   assertEquals(books,count("SELECT count(*) FROM books"));assertEquals(batches,count("SELECT count(*) FROM catalog_import_batches"));
   assertEquals(categories,count("SELECT count(*) FROM categories"));assertEquals(0,count("SELECT count(*) FROM book_sources"));
  } finally {
   sql.update("DROP TRIGGER test_reject_import ON book_sources",Map.of());sql.update("DROP FUNCTION test_reject_import()",Map.of());
  }
 }
 @Test void concurrentCatalogueImportsCreateOneSourceAndOneBook() throws Exception {
  var document=new CatalogImportService.Document("fanqie",List.of(importedBook("7000000000000000006","并发导入","网络小说")));
  try(var workers=Executors.newFixedThreadPool(2)) {
   var start=new CountDownLatch(1);
   var first=workers.submit(()->{start.await();return catalogImports.importDocument(document);});
   var second=workers.submit(()->{start.await();return catalogImports.importDocument(document);});start.countDown();
   var a=first.get(10,TimeUnit.SECONDS);var b=second.get(10,TimeUnit.SECONDS);
   assertEquals(1,a.inserted()+b.inserted());assertEquals(1,a.skipped()+b.skipped());
   assertEquals(1,count("SELECT count(*) FROM book_sources"));assertEquals(2,count("SELECT count(*) FROM books"));
  }
 }
 @Test void borrowReturnAndDamageKeepInventoryConsistent() {
  UUID loan=id(borrow(readerId,copies.getFirst()));
  assertEquals("borrowed",sql.one("SELECT status FROM book_copies WHERE id=:id",Sql.params("id",copies.getFirst())).get("status"));
  circulation.giveBack(UUID.randomUUID(),loan,new CirculationController.Return(true,"书页破损"));
  assertEquals("unavailable",sql.one("SELECT status FROM book_copies WHERE id=:id",Sql.params("id",copies.getFirst())).get("status"));
  assertEquals(0,count("SELECT count(*) FROM loans WHERE returned_at IS NULL"));
  assertThrows(BusinessException.class,()->circulation.giveBack(UUID.randomUUID(),loan,new CirculationController.Return(false,"")));
 }
 @Test void sameCopyConcurrentBorrowSucceedsOnce() throws Exception {
  try(var workers=Executors.newFixedThreadPool(2)) {
   var start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
   for(UUID reader:List.of(readerId,otherId)) futures.add(workers.submit(()->{asAdmin();start.await();try{borrow(reader,copies.getFirst());return true;}catch(Exception e){return false;}finally{cleanupAuth();}}));
   start.countDown();int successes=0;for(var future:futures)if(future.get(15,TimeUnit.SECONDS))successes++;
   assertEquals(1,successes);assertEquals(1,count("SELECT count(*) FROM loans WHERE returned_at IS NULL"));
  }
 }
 @Test void concurrentBorrowDoesNotExceedReaderLimit() throws Exception {
  for(int i=0;i<4;i++)borrow(readerId,copies.get(i));
  try(var workers=Executors.newFixedThreadPool(2)) {
   var start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
   for(int i=4;i<6;i++){UUID copy=copies.get(i);futures.add(workers.submit(()->{asAdmin();start.await();try{borrow(readerId,copy);return true;}catch(Exception e){return false;}finally{cleanupAuth();}}));}
   start.countDown();int successes=0;for(var future:futures)if(future.get(15,TimeUnit.SECONDS))successes++;
   assertEquals(1,successes);assertEquals(5,count("SELECT count(*) FROM loans WHERE returned_at IS NULL"));
  }
 }
 @Test void retriesReuseResultAndRejectChangedPayload() {
  UUID key=UUID.randomUUID();var request=new CirculationController.Borrow(readerId,copies.getFirst(),"");
  var first=circulation.borrow(key,request);var again=circulation.borrow(key,request);assertEquals(first.get("id"),again.get("id"));
  assertEquals(1,count("SELECT count(*) FROM loans"));
  assertThrows(BusinessException.class,()->circulation.borrow(key,new CirculationController.Borrow(readerId,copies.get(1),"")));
 }
 @Test void failureRollsBackLoanStateAndRequestKey() {
  sql.update("CREATE FUNCTION test_fail_copy() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'simulated'; END$$",Map.of());
  sql.update("CREATE TRIGGER test_copy_failure BEFORE UPDATE ON book_copies FOR EACH ROW EXECUTE FUNCTION test_fail_copy()",Map.of());
  try { assertThrows(Exception.class,()->borrow(readerId,copies.getFirst()));assertEquals(0,count("SELECT count(*) FROM loans"));assertEquals(0,count("SELECT count(*) FROM idempotency_keys")); }
  finally {sql.update("DROP TRIGGER test_copy_failure ON book_copies",Map.of());sql.update("DROP FUNCTION test_fail_copy()",Map.of());}
  assertEquals("available",sql.one("SELECT status FROM book_copies WHERE id=:id",Sql.params("id",copies.getFirst())).get("status"));
 }
 @Test void returnFailureRollsBackBothSides() {
  UUID loan=id(borrow(readerId,copies.getFirst()));
  sql.update("CREATE FUNCTION test_fail_return() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'simulated'; END$$",Map.of());
  sql.update("CREATE TRIGGER test_return_failure BEFORE UPDATE ON book_copies FOR EACH ROW EXECUTE FUNCTION test_fail_return()",Map.of());
  try { assertThrows(Exception.class,()->circulation.giveBack(UUID.randomUUID(),loan,new CirculationController.Return(false,"")));assertEquals(1,count("SELECT count(*) FROM loans WHERE returned_at IS NULL")); }
  finally {sql.update("DROP TRIGGER test_return_failure ON book_copies",Map.of());sql.update("DROP FUNCTION test_fail_return()",Map.of());}
 }
 @Test void concurrentRenewalExtendsOnlyOnce() throws Exception {
  UUID loan=id(borrow(readerId,copies.getFirst()));var initial=sql.one("SELECT due_date FROM loans WHERE id=:id",Sql.params("id",loan));
  try(var workers=Executors.newFixedThreadPool(2)) {
   var start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
   for(int i=0;i<2;i++)futures.add(workers.submit(()->{asAdmin();start.await();try{circulation.renew(UUID.randomUUID(),loan);return true;}catch(Exception e){return false;}finally{cleanupAuth();}}));
   start.countDown();int successes=0;for(var future:futures)if(future.get(15,TimeUnit.SECONDS))successes++;
   assertEquals(1,successes);assertEquals(1,count("SELECT count(*) FROM loan_renewals"));
  }
  assertEquals(LocalDate.parse((String)initial.get("due_date")).plusDays(30).toString(),sql.one("SELECT due_date FROM loans WHERE id=:id",Sql.params("id",loan)).get("due_date"));
 }
 @Test void archiveAndStateMaintenanceCannotBypassBorrowing() {
  borrow(readerId,copies.getFirst());assertThrows(BusinessException.class,()->catalog.archive(bookId,true));
  assertThrows(BusinessException.class,()->catalog.copyState(copies.getFirst(),new CatalogController.CopyState("available",null,"","")));
  assertThrows(Exception.class,()->tx.executeWithoutResult(s->sql.update("UPDATE book_copies SET status='available' WHERE id=:id",Sql.params("id",copies.getFirst()))));
 }
 @Test void disabledReaderCanStillReturnButCannotRenew() {
  UUID loan=id(borrow(readerId,copies.getFirst()));readers.account(readerId,false,null);
  assertThrows(BusinessException.class,()->borrow(readerId,copies.get(1)));assertThrows(BusinessException.class,()->circulation.renew(UUID.randomUUID(),loan));
  circulation.giveBack(UUID.randomUUID(),loan,new CirculationController.Return(false,""));assertEquals(0,count("SELECT count(*) FROM loans WHERE returned_at IS NULL"));
 }
 @Test void isbnFormatsCanonicalizeAndRejectInvalidValues() {
  assertEquals("9780306406157",Isbn.normalize("0-306-40615-2"));assertEquals("9780306406157",Isbn.normalize("978-0-306-40615-7"));
  assertThrows(BusinessException.class,()->Isbn.normalize("9780306406158"));assertNull(Isbn.normalize(""));
 }
 @Test void readerCannotRenewOthersOrReadAdminList() {
  UUID loan=id(borrow(otherId,copies.getFirst()));auth(new CurrentUser(readerUser,"test_reader","reader",false));
  assertThrows(BusinessException.class,()->circulation.renew(UUID.randomUUID(),loan));
  assertThrows(BusinessException.class,()->circulation.list(false,"","all","all",null,null,null,1,20));
  var mine=circulation.list(true,"","all","all",null,null,null,1,20);assertEquals(0L,mine.get("total"));
 }
 @Test void categoryReferencesAndCopyIdentityAreProtected() {
  assertThrows(Exception.class,()->catalog.deleteCategory(categoryId));
  assertThrows(Exception.class,()->sql.update("UPDATE book_copies SET code='CHANGED' WHERE id=:id",Sql.params("id",copies.getFirst())));
 }
 @Test void overdueDatesIncludeDueDayAndPreserveReturnedOverdueHistory() {
  var loans=new ArrayList<UUID>();
  for(int i=0;i<2;i++){final int offset=i;loans.add(tx.execute(s->{
   var row=sql.one("INSERT INTO loans(reader_id,copy_id,borrowed_by,borrowed_at,original_due_date,due_date) VALUES(:reader,:copy,:actor,((library_today()-:age)::timestamp AT TIME ZONE 'Asia/Shanghai'),library_today()-:offset,library_today()-:offset) RETURNING id",Sql.params("reader",readerId,"copy",copies.get(offset),"actor",adminId,"age",30+offset,"offset",offset));
   sql.update("UPDATE book_copies SET status='borrowed' WHERE id=:id",Sql.params("id",copies.get(offset)));return id(row);
  }));}
  var overdue=circulation.list(false,"","all","overdue",null,null,null,1,20);assertEquals(1L,overdue.get("total"));
  var dueSoon=circulation.list(false,"","all","dueSoon",null,null,null,1,20);assertEquals(1L,dueSoon.get("total"));
  assertThrows(BusinessException.class,()->borrow(readerId,copies.get(2)));
  circulation.giveBack(UUID.randomUUID(),loans.get(1),new CirculationController.Return(false,""));
  assertEquals(0L,circulation.list(false,"","all","overdue",null,null,null,1,20).get("total"));
  assertEquals(1L,circulation.list(false,"","all","returnedOverdue",null,null,null,1,20).get("total"));
  circulation.renew(UUID.randomUUID(),loans.getFirst());
 }
 HttpClient browser() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); }
 HttpResponse<String> request(HttpClient browser,String path,String method,Object body,boolean csrf) throws Exception {
  var base="http://127.0.0.1:"+environment.getProperty("local.server.port")+"/api/v1";
  var builder=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json");
  if(csrf){var token=browser.send(HttpRequest.newBuilder(URI.create(base+"/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());builder.header("X-XSRF-TOKEN",json.readTree(token.body()).get("token").asText());}
  builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
  return browser.send(builder.build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test void httpAuthenticationCsrfAndPasswordRevocation() throws Exception {
  var browser=browser();assertEquals(401,request(browser,"/books","GET",null,false).statusCode());
  assertEquals(403,request(browser,"/auth/login","POST",Map.of("username","test_reader","password",password),false).statusCode());
  assertEquals(200,request(browser,"/auth/login","POST",Map.of("username","test_reader","password",password),true).statusCode());
  assertEquals(200,request(browser,"/books","GET",null,false).statusCode());assertEquals(403,request(browser,"/readers","GET",null,false).statusCode());
  String replacement=AuthService.randomToken();assertEquals(200,request(browser,"/auth/password","POST",Map.of("oldPassword",password,"newPassword",replacement),true).statusCode());
  assertEquals(401,request(browser,"/auth/me","GET",null,false).statusCode());assertEquals(0,count("SELECT count(*) FROM sessions"));
  assertEquals(401,request(browser,"/auth/login","POST",Map.of("username","test_reader","password",password),true).statusCode());
 }
 @Test void initialPasswordBlocksBusinessUntilChanged() throws Exception {
  sql.update("UPDATE users SET must_change_password=true WHERE id=:id",Sql.params("id",readerUser));
  var browser=browser();assertEquals(200,request(browser,"/auth/login","POST",Map.of("username","test_reader","password",password),true).statusCode());
  assertEquals(403,request(browser,"/books","GET",null,false).statusCode());assertEquals(200,request(browser,"/auth/me","GET",null,false).statusCode());
 }
 @Test void fiveCharacterPasswordsWorkForReaderCreationLoginAndChange() throws Exception {
  var admin=browser();assertEquals(200,request(admin,"/auth/login","POST",Map.of("username","test_admin","password",password),true).statusCode());
  String initial=AuthService.randomToken().substring(0,5);
  assertEquals(400,request(admin,"/readers","POST",Map.of("username","min_reader","name","长度边界读者","password",initial.substring(0,4)),true).statusCode());
  assertEquals(200,request(admin,"/readers","POST",Map.of("username","min_reader","name","长度边界读者","password",initial),true).statusCode());
  var reader=browser();assertEquals(200,request(reader,"/auth/login","POST",Map.of("username","min_reader","password",initial),true).statusCode());
  String replacement;do{replacement=AuthService.randomToken().substring(0,5);}while(replacement.equals(initial));
  assertEquals(200,request(reader,"/auth/password","POST",Map.of("oldPassword",initial,"newPassword",replacement),true).statusCode());
  assertEquals(200,request(reader,"/auth/login","POST",Map.of("username","min_reader","password",replacement),true).statusCode());
  assertEquals(200,request(reader,"/books","GET",null,false).statusCode());
 }
 @Test void crossOriginWritesRejectedAndAdminOpenApiAccessible() throws Exception {
  var browser=browser();assertEquals(200,request(browser,"/auth/login","POST",Map.of("username","test_admin","password",password),true).statusCode());
  String base="http://127.0.0.1:"+environment.getProperty("local.server.port");
  var token=browser.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
  var rejected=browser.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/logout")).header("Origin","https://untrusted.invalid").header("X-XSRF-TOKEN",json.readTree(token.body()).get("token").asText()).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
  assertEquals(403,rejected.statusCode());
  var schema=browser.send(HttpRequest.newBuilder(URI.create(base+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,schema.statusCode());assertTrue(json.readTree(schema.body()).has("paths"));
 }
}
